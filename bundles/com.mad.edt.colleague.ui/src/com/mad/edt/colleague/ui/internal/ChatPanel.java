package com.mad.edt.colleague.ui.internal;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * One chat conversation tab bound to a dedicated {@link ChatSession}.
 * <p>
 * The conversation is rendered by the bundled {@code web/chat.html} page
 * inside an embedded Edge browser: bubbles, markdown answers and tool chips
 * are drawn by the page; the Java side receives bridge calls ({@code send},
 * {@code newChat}, {@code toggleEdit}, ...) and pushes events back through
 * {@code window.colleaguePush(kind, json)}. When no browser backend can be
 * created the panel falls back to a plain styled-text conversation so the
 * feature keeps working everywhere.
 */
public final class ChatPanel {

    /** Model round-trips allowed for one edit-mode question. */
    private static final int AGENT_MAX_STEPS = 8;

    /** Max gap between two ESC presses that toggles the edit mode. */
    private static final long ESC_WINDOW_MS = 1_000L;

    /** Where the WebView2 user data folder lives; set before browser creation. */
    private static final String EDGE_DATA_DIR_PROPERTY = "org.eclipse.swt.browser.EdgeDataDir";

    private static boolean ourEdgeDirChecked;

    /** Live panels, kept in sync when the process-wide edit mode changes. */
    private static final List<ChatPanel> OUR_PANELS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ChatSession ourSession;
    private final String ourModel;

    private Composite ourRoot;
    private Browser ourBrowser;
    private File ourPageFile;
    private boolean ourBridgeReady;
    private final List<String> ourPendingPushes = new ArrayList<>();

    private StyledText ourConversation;
    private Text ourQuestion;
    private Button ourAskButton;
    private Label ourStatusLabel;
    private Button ourModeButton;

    private final List<StyleRange> ourStyleRanges = new ArrayList<>();
    private Color ourUserBubble;
    private Color ourAnswerBubble;
    private Color ourErrorBubble;

    private String ourAttachedContext = "";
    private String ourLastAnswer = "";
    private String ourCachedQuestion = "";
    private boolean ourBusy;
    private Consumer<String> ourAnswerListener;
    private Supplier<List<IToolExecutor>> ourToolSupplier;
    private Function<String, String> ourAutoContextSupplier;
    private long ourLastEscapeAt;

    /**
     * @param theParent the tab body composite, laid out by the caller
     * @param theSession session holding the conversation state
     * @param theModel model name to ask, {@code null} for the provider default
     */
    public ChatPanel(final Composite theParent, final ChatSession theSession, final String theModel) {
        ourSession = theSession;
        ourModel = theModel;
        createControls(theParent);
        OUR_PANELS.add(this);
    }

    /**
     * @return the top-level control to place inside the tab
     */
    public Control getControl() {
        return ourRoot;
    }

    /** Puts focus into the question box. */
    public void setFocus() {
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            ourBrowser.setFocus();
            return;
        }
        if(ourQuestion != null && !ourQuestion.isDisposed()) {
            ourQuestion.setFocus();
        }
    }

    /**
     * Places the text into the question box without sending it.
     *
     * @param theQuestion question text
     */
    public void fillQuestion(final String theQuestion) {
        ourCachedQuestion = theQuestion;
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("text", theQuestion);
            push("input", payload);
            return;
        }
        if(ourQuestion != null && !ourQuestion.isDisposed()) {
            ourQuestion.setText(theQuestion);
        }
        setFocus();
    }

    /**
     * Attaches project context to the next question.
     *
     * @param theContext context text
     */
    public void attachContext(final String theContext) {
        if(theContext != null && !theContext.isEmpty()) {
            ourAttachedContext = theContext;
        }
    }

    /** Sends the current question box content. */
    public void send() {
        final String question = ourCachedQuestion;
        ourCachedQuestion = "";
        sendQuestion(question);
    }

    /**
     * @return the answer of the last successful question, never {@code null}
     */
    public String getLastAnswer() {
        return ourLastAnswer;
    }

    /**
     * @param theListener invoked on the UI thread with every non-empty answer
     */
    public void setAnswerListener(final Consumer<String> theListener) {
        ourAnswerListener = theListener;
    }

    /**
     * @param theToolSupplier workspace tools for the agent loop, consulted at
     *            each question; {@code null} disables the agent path entirely
     */
    public void setToolSupplier(final Supplier<List<IToolExecutor>> theToolSupplier) {
        ourToolSupplier = theToolSupplier;
    }

    /**
     * @param theSupplier takes the question text and produces search snippets
     *            for it when no context was attached manually; runs on a
     *            background thread
     */
    public void setAutoContextSupplier(final Function<String, String> theSupplier) {
        ourAutoContextSupplier = theSupplier;
    }

    /** Clears the conversation and the stored session history. */
    private void reset() {
        ourSession.reset();
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            pushHistory();
            setStatus("Диалог сброшен");
            return;
        }
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.setText("");
        }
        ourStyleRanges.clear();
        ourLastAnswer = "";
        setStatus("Диалог сброшен");
    }

    /** Releases the SWT controls. */
    public void dispose() {
        OUR_PANELS.remove(this);
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            ourBrowser.dispose();
        }
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.dispose();
        }
        if(ourRoot != null && !ourRoot.isDisposed()) {
            ourRoot.dispose();
        }
        for(final Color color : new Color[] {ourUserBubble, ourAnswerBubble,
            ourErrorBubble}) {
            if(color != null && !color.isDisposed()) {
                color.dispose();
            }
        }
        final File page = ourPageFile;
        ourPageFile = null;
        if(page != null) {
            try {
                Files.deleteIfExists(page.toPath());
            } catch (final Exception ignored) {
                // temp file, the OS cleans the temp directory eventually
            }
        }
    }

    private void createControls(final Composite theParent) {
        ourRoot = new Composite(theParent, SWT.NONE);
        ourRoot.setLayout(new GridLayout(1, false));
        ourRoot.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ourRoot.addListener(SWT.Dispose, event -> OUR_PANELS.remove(ChatPanel.this));
        if(createBrowser()) {
            return;
        }
        createFallback(ourRoot);
    }

    /**
     * Tries the Edge browser backend with the bundled chat page.
     *
     * @return {@code true} when the browser path is active
     */
    private boolean createBrowser() {
        try {
            ensureEdgeDataDir();
            ourBrowser = new Browser(ourRoot, SWT.NONE);
        } catch (final RuntimeException | LinkageError | org.eclipse.swt.SWTError ex) {
            System.err.println("colleague: embedded browser unavailable, plain fallback: "
                + ex.getMessage());
            ourBrowser = null;
        }
        if(ourBrowser == null) {
            return false;
        }
        ourBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        new BridgeFunction(ourBrowser, "colleague");
        if(!loadPage()) {
            ourBrowser.dispose();
            ourBrowser = null;
            return false;
        }
        return true;
    }

    /** WebView2 refuses its default user-data location next to java.exe; pick temp. */
    private static synchronized void ensureEdgeDataDir() {
        if(ourEdgeDirChecked) {
            return;
        }
        ourEdgeDirChecked = true;
        if(System.getProperty(EDGE_DATA_DIR_PROPERTY) != null) {
            return;
        }
        final File dir = new File(System.getProperty("java.io.tmpdir", "."), "colleague-webview2");
        if(!dir.exists() && !dir.mkdirs()) {
            return;
        }
        System.setProperty(EDGE_DATA_DIR_PROPERTY, dir.getAbsolutePath());
    }

    /** Loads {@code web/chat.html} from the bundle as the browser content. */
    private boolean loadPage() {
        try {
            final java.net.URL entry = org.eclipse.core.runtime.Platform.getBundle(
                ColleagueUIPlugin.PLUGIN_ID).getEntry("web/chat.html");
            if(entry == null) {
                System.err.println("colleague: web/chat.html missing from the bundle");
                return false;
            }
            final StringBuilder text = new StringBuilder();
            try (InputStream stream = entry.openStream();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                final char[] buffer = new char[8192];
                int read;
                while((read = reader.read(buffer)) > 0) {
                    text.append(buffer, 0, read);
                }
            }
            if(!ourBrowser.setText(text.toString())) {
                System.err.println("colleague: setText on the browser was refused");
                return false;
            }
            final JsonObject ready = new JsonObject();
            ready.addProperty("theme", preferredTheme());
            ready.addProperty("name", "Colleague");
            ready.addProperty("sub", ourModel == null || ourModel.isEmpty()
                ? "ассистент по проекту" : ourModel);
            ready.addProperty("mode", Boolean.valueOf(AgentMode.isOn()));
            push("ready", ready);
            return true;
        } catch (final Exception ex) {
            System.err.println("colleague: chat page load failed: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Dark or light page theme picked from the workbench widget colors so the
     * chat blends into the EDT skin.
     */
    private String preferredTheme() {
        final Display display = ourRoot.getDisplay();
        final Color base = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        final RGB rgb = base.getRGB();
        return (rgb.red + rgb.green + rgb.blue) / 3 < 128 ? "dark" : "light";
    }

    /** Sends one event to the page; queues it until the page answers bridgeReady. */
    private void push(final String theKind, final JsonObject thePayload) {
        final String call = "window.colleaguePush('" + theKind + "','"
            + escapeJs(thePayload.toString()) + "')";
        if(!ourBridgeReady) {
            ourPendingPushes.add(call);
            return;
        }
        if(ourBrowser == null || ourBrowser.isDisposed()) {
            return;
        }
        final Runnable send = () -> {
            if(ourBrowser != null && !ourBrowser.isDisposed()) {
                ourBrowser.execute(call);
            }
        };
        if(ourRoot != null && !ourRoot.isDisposed()
            && ourRoot.getDisplay().getThread() == Thread.currentThread()) {
            send.run();
        } else {
            Display.getDefault().asyncExec(send);
        }
    }

    private static String escapeJs(final String theText) {
        return theText.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\r", "\\r").replace("\n", "\\n").replace("<", "\\u003c");
    }

    /** Replays the stored conversation into the page after a reload or reset. */
    private void pushHistory() {
        final JsonArray items = new JsonArray();
        for(final ChatMessage message : ourSession.snapshotHistory()) {
            if(ChatMessage.ROLE_USER.equals(message.getRole())) {
                final JsonObject item = new JsonObject();
                item.addProperty("kind", "user");
                item.addProperty("text", message.getContent());
                items.add(item);
            } else if(ChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                final JsonObject item = new JsonObject();
                item.addProperty("kind", "answer");
                item.addProperty("text", message.getContent());
                items.add(item);
            }
        }
        final JsonObject payload = new JsonObject();
        payload.add("items", items);
        push("history", payload);
    }

    /** Bridge object exposed to the page as {@code window.colleague}. */
    private final class BridgeFunction extends BrowserFunction {

        private BridgeFunction(final Browser theBrowser, final String theName) {
            super(theBrowser, theName);
        }

        @Override
        public Object function(final Object... theArguments) {
            if(theArguments.length < 1 || !(theArguments[0] instanceof String)) {
                return Boolean.FALSE;
            }
            final String method = (String)theArguments[0];
            final JsonObject args = parseArgs(theArguments.length > 1 ? theArguments[1] : null);
            switch(method) {
                case "bridgeReady":
                    if(ourBridgeReady) {
                        return Boolean.TRUE;
                    }
                    ourBridgeReady = true;
                    for(final String call : new ArrayList<>(ourPendingPushes)) {
                        if(ourBrowser != null && !ourBrowser.isDisposed()) {
                            ourBrowser.execute(call);
                        }
                    }
                    ourPendingPushes.clear();
                    pushHistory();
                    return Boolean.TRUE;
                case "send":
                    sendQuestion(optString(args, "text"));
                    return Boolean.TRUE;
                case "newChat":
                    reset();
                    return Boolean.TRUE;
                case "toggleEdit":
                    final boolean on = AgentMode.toggle();
                    refreshModeIndicators();
                    setStatus(on
                        ? "Режим правки ВКЛЮЧЁН — агент с инструментами проекта"
                        : "Режим правки ВЫКЛЮЧЕН");
                    return Boolean.TRUE;
                case "copy":
                    copyToClipboard(optString(args, "text"));
                    return Boolean.TRUE;
                case "attachFile":
                    setStatus("Файлы пока не прикрепляются — приложите контекст кнопкой на панели контактов");
                    return Boolean.TRUE;
                default:
                    return Boolean.FALSE;
            }
        }
    }

    private static JsonObject parseArgs(final Object theArgument) {
        if(theArgument instanceof String) {
            try {
                final JsonElement parsed = JsonParser.parseString((String)theArgument);
                if(parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (final RuntimeException ignored) {
                // fall through to empty object below
            }
        }
        return new JsonObject();
    }

    private static String optString(final JsonObject theObject, final String theName) {
        return theObject.has(theName) && !theObject.get(theName).isJsonNull()
            ? theObject.get(theName).getAsString() : "";
    }

    private void copyToClipboard(final String theText) {
        if(theText == null || theText.isEmpty()) {
            return;
        }
        final org.eclipse.swt.dnd.Clipboard clipboard =
            new org.eclipse.swt.dnd.Clipboard(ourRoot.getDisplay());
        clipboard.setContents(
            new Object[] {theText},
            new org.eclipse.swt.dnd.Transfer[] {org.eclipse.swt.dnd.TextTransfer.getInstance()});
        if(ourRoot != null && !ourRoot.isDisposed()) {
            ourRoot.addListener(SWT.Dispose, event -> clipboard.dispose());
        } else {
            clipboard.dispose();
        }
    }

    /** Plain styled-text conversation used when no browser backend is available. */
    private void createFallback(final Composite theParent) {
        ourConversation = new StyledText(theParent,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        ourConversation.setMargins(6, 6, 6, 2);
        ourConversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        initBubbleColors();

        final Composite header = new Composite(theParent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        final GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        header.setLayout(headerLayout);
        final Button resetButton = new Button(header, SWT.PUSH);
        resetButton.setText("Новый диалог");
        resetButton.addListener(SWT.Selection, event -> reset());
        ourModeButton = new Button(header, SWT.TOGGLE);
        ourModeButton.addListener(SWT.Selection, event -> {
            AgentMode.setOn(ourModeButton.getSelection());
            refreshModeIndicators();
            setStatus(AgentMode.isOn()
                ? "Режим правки ВКЛЮЧЁН — клик по кнопке «⚙» выключит"
                : "Режим правки ВЫКЛЮЧЕН");
        });
        updateModeIndicator();

        final Composite input = new Composite(theParent, SWT.NONE);
        input.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        final GridLayout inputLayout = new GridLayout(2, false);
        inputLayout.marginWidth = 0;
        inputLayout.marginHeight = 4;
        input.setLayout(inputLayout);

        ourQuestion = new Text(input, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        final GridData questionData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        questionData.heightHint = 80;
        ourQuestion.setLayoutData(questionData);
        ourQuestion.addTraverseListener(event -> {
            if(event.detail == SWT.TRAVERSE_RETURN && (event.stateMask & SWT.CTRL) != 0) {
                event.doit = true;
                sendQuestion(ourQuestion.getText());
            }
        });
        ourQuestion.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent theEvent) {
                if(theEvent.keyCode == SWT.ESC) {
                    registerEscape();
                }
            }
        });

        ourAskButton = new Button(input, SWT.PUSH);
        ourAskButton.setText("Спросить");
        ourAskButton.addListener(SWT.Selection, event -> sendQuestion(ourQuestion.getText()));

        ourStatusLabel = new Label(theParent, SWT.NONE);
        ourStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    }

    /**
     * Shared send path for both renderers: echo the question, run the model
     * (or the agent loop in the edit mode) on a background job, present the
     * answer.
     */
    private void sendQuestion(final String theQuestion) {
        final String question = theQuestion == null ? "" : theQuestion.trim();
        if(question.isEmpty() || ourBusy) {
            return;
        }
        final String context = ourAttachedContext;
        ourAttachedContext = "";
        final boolean editMode = AgentMode.isOn() && ourToolSupplier != null;
        final boolean browser = ourBrowser != null && !ourBrowser.isDisposed();
        if(browser) {
            final JsonObject user = new JsonObject();
            user.addProperty("text", question);
            user.addProperty("time", timeLabel());
            push("user", user);
        } else {
            if(!context.isEmpty()) {
                appendLine("Вы: " + question + "  [к вопросу прикреплён контекст проекта, "
                    + context.length() + " символов, остаётся в диалоге до «Нового диалога»]");
            } else if(editMode) {
                appendLine("Вы: " + question + "  [режим правки: агент с инструментами проекта]");
            } else {
                appendLine("Вы: " + question + "  [контекст проекта: автоматический поиск]");
            }
            if(ourQuestion != null && !ourQuestion.isDisposed()) {
                ourQuestion.setText("");
            }
        }
        ourBusy = true;
        if(ourAskButton != null && !ourAskButton.isDisposed()) {
            ourAskButton.setEnabled(false);
        }
        setStatus(editMode ? "Агент: ищу по проекту и исполняю инструменты..." : "Думаю...");

        Job.create("Colleague: question", (monitor) -> {
            try {
                final String effectiveContext = context.isEmpty() && ourAutoContextSupplier != null
                    ? applyOrEmpty(ourAutoContextSupplier, question) : context;
                final String answer = editMode
                    ? runAgent(question, effectiveContext)
                    : ourSession.ask(ourModel, effectiveContext, question);
                final int split = answer.lastIndexOf("\n--- ");
                final String body = split > 0 ? answer.substring(0, split).strip() : answer;
                final String stats = split > 0 ? answer.substring(split + 1).strip() : "";
                ourLastAnswer = body;
                Display.getDefault().asyncExec(() -> {
                    if(browser) {
                        final JsonObject payload = new JsonObject();
                        payload.addProperty("text", body);
                        payload.addProperty("time", timeLabel());
                        push("answer", payload);
                    } else {
                        appendLine("Colleague: " + body);
                    }
                    if(ourAnswerListener != null) {
                        ourAnswerListener.accept(body);
                    }
                });
                setStatus(stats);
                return Status.OK_STATUS;
            } catch (final Exception ex) {
                Display.getDefault().asyncExec(() -> {
                    if(browser) {
                        final JsonObject payload = new JsonObject();
                        payload.addProperty("text", String.valueOf(ex.getMessage()));
                        payload.addProperty("time", timeLabel());
                        push("error", payload);
                    } else {
                        appendLine("Ошибка: " + ex.getMessage());
                    }
                });
                return new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID, "assistant call failed", ex);
            } finally {
                ourBusy = false;
                Display.getDefault().asyncExec(() -> {
                    if(ourAskButton != null && !ourAskButton.isDisposed()) {
                        ourAskButton.setEnabled(true);
                    }
                    if(browser) {
                        final JsonObject busy = new JsonObject();
                        busy.addProperty("busy", Boolean.FALSE);
                        push("busy", busy);
                    }
                });
            }
        }).schedule();
    }

    /**
     * Edit-mode path: the agent loop may call workspace tools (every write is
     * confirmed by the user) before it answers; the exchange is remembered in
     * the session history like a plain question.
     */
    private String runAgent(final String theQuestion, final String theContext)
        throws com.mad.edt.colleague.llm.LLMException {
        final AgentLoop loop = new AgentLoop(ourSession.getProvider(),
            ourToolSupplier.get(), ourModel, AGENT_MAX_STEPS);
        final StringBuilder system = new StringBuilder();
        if(ourSession.getSystemPrompt() != null && !ourSession.getSystemPrompt().isEmpty()) {
            system.append(ourSession.getSystemPrompt()).append(' ');
        }
        system.append("You can call the provided workspace tools (search_code, list_files,")
            .append(" read_file, write_file). Search the project before answering code")
            .append(" questions; quote paths and line numbers. Every write is confirmed by")
            .append(" the user, so propose exact complete file contents. Answer in the")
            .append(" language of the question.");
        String task = theQuestion;
        if(!theContext.isEmpty()) {
            task = task + "\n\nProject context found by search:\n" + theContext;
        }
        final long startedAt = System.nanoTime();
        final AgentAnswer answer = loop.run(system.toString(), task);
        ourSession.noteExchange(theQuestion, answer.getText());
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return answer.getText() + String.format(
            "%n--- режим правки: %d шагов, %d вызовов инструментов, %d мс",
            Integer.valueOf(answer.getSteps()), Integer.valueOf(answer.getExecutedToolCalls()),
            Long.valueOf(elapsedMs));
    }

    private void registerEscape() {
        final long now = System.currentTimeMillis();
        if(ourLastEscapeAt > 0L && now - ourLastEscapeAt < ESC_WINDOW_MS) {
            ourLastEscapeAt = 0L;
            final boolean on = AgentMode.toggle();
            refreshModeIndicators();
            setStatus(on
                ? "Режим правки ВКЛЮЧЁН — два ESC или клик по кнопке «⚙» выключат"
                : "Режим правки ВЫКЛЮЧЕН");
        } else {
            ourLastEscapeAt = now;
            setStatus("Ещё раз ESC — переключить режим правки");
        }
    }

    /** Redraws the edit-mode indicator of every live panel. */
    private static void refreshModeIndicators() {
        for(final ChatPanel panel : OUR_PANELS) {
            panel.updateModeIndicator();
        }
    }

    private void updateModeIndicator() {
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("on", Boolean.valueOf(AgentMode.isOn()));
            push("mode", payload);
            return;
        }
        if(ourModeButton == null || ourModeButton.isDisposed()) {
            return;
        }
        ourModeButton.setSelection(AgentMode.isOn());
        ourModeButton.setText(AgentMode.isOn()
            ? "\u2699 режим правки: вкл" : "\u2699 режим правки: выкл");
    }

    private static String applyOrEmpty(final Function<String, String> theFunction,
        final String theArgument) {
        try {
            final String value = theFunction.apply(theArgument);
            return value == null ? "" : value;
        } catch (final RuntimeException ex) {
            return "";
        }
    }

    private static String timeLabel() {
        return new SimpleDateFormat("HH:mm").format(new Date());
    }

    private void appendLine(final String theText) {
        if(ourConversation == null || ourConversation.isDisposed()) {
            return;
        }
        if(ourConversation.getCharCount() > 0) {
            ourConversation.append("\n\n");
        }
        final boolean user = theText.startsWith("Вы:");
        final boolean error = theText.startsWith("Ошибка:");
        final String bubbleText = user ? "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0"
            + theText : theText;
        final int start = ourConversation.getCharCount();
        ourConversation.append(bubbleText);
        final Color bubble = error ? ourErrorBubble : (user ? ourUserBubble : ourAnswerBubble);
        final int prefixEnd = bubbleText.indexOf(':') + 1;
        if(prefixEnd > 1) {
            final StyleRange header = new StyleRange(start, prefixEnd, null, bubble);
            header.fontStyle = SWT.BOLD;
            ourStyleRanges.add(header);
            ourStyleRanges.add(new StyleRange(start + prefixEnd,
                bubbleText.length() - prefixEnd, null, bubble));
        } else {
            ourStyleRanges.add(new StyleRange(start, bubbleText.length(), null, bubble));
        }
        ourConversation.setStyleRanges(
            ourStyleRanges.toArray(new StyleRange[ourStyleRanges.size()]));
        ourConversation.setSelection(start + bubbleText.length());
        ourConversation.showSelection();
    }

    private void initBubbleColors() {
        final Display display = ourConversation.getDisplay();
        final Color base = ourConversation.getBackground() == null
            ? display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND)
            : ourConversation.getBackground();
        final RGB baseRgb = base.getRGB();
        final boolean dark = (baseRgb.red + baseRgb.green + baseRgb.blue) / 3 < 128;
        if(dark) {
            ourUserBubble = mix(display, baseRgb, new RGB(64, 100, 148), 0.55);
            ourAnswerBubble = mix(display, baseRgb, new RGB(74, 80, 92), 0.6);
            ourErrorBubble = mix(display, baseRgb, new RGB(150, 74, 74), 0.55);
        } else {
            ourUserBubble = mix(display, baseRgb, new RGB(120, 170, 230), 0.3);
            ourAnswerBubble = mix(display, baseRgb, new RGB(214, 219, 226), 0.45);
            ourErrorBubble = mix(display, baseRgb, new RGB(240, 178, 178), 0.45);
        }
    }

    private static Color mix(final Display theDisplay, final RGB theBase, final RGB theTarget,
        final double theFactor) {
        final double rest = 1.0 - theFactor;
        return new Color(theDisplay,
            (int)(theBase.red * rest + theTarget.red * theFactor),
            (int)(theBase.green * rest + theTarget.green * theFactor),
            (int)(theBase.blue * rest + theTarget.blue * theFactor));
    }

    private void setStatus(final String theText) {
        if(ourBrowser != null && !ourBrowser.isDisposed()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("text", theText == null ? "" : theText);
            push("status", payload);
            return;
        }
        if(ourStatusLabel == null || ourStatusLabel.isDisposed()) {
            return;
        }
        if(Display.getDefault().getThread() == Thread.currentThread()) {
            ourStatusLabel.setText(theText);
        } else {
            Display.getDefault().asyncExec(() -> {
                if(!ourStatusLabel.isDisposed()) {
                    ourStatusLabel.setText(theText);
                }
            });
        }
    }
}

