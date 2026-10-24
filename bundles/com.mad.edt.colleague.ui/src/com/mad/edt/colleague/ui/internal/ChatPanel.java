package com.mad.edt.colleague.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
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

import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * One chat conversation tab: history area, question input and status line
 * bound to a dedicated {@link ChatSession}. Extracted from the view so the
 * view shell can host several tabs side by side.
 */
public final class ChatPanel {

    /** Model round-trips allowed for one edit-mode question. */
    private static final int AGENT_MAX_STEPS = 8;

    /** Max gap between two ESC presses that toggles the edit mode. */
    private static final long ESC_WINDOW_MS = 800L;

    private final ChatSession ourSession;
    private final String ourModel;

    private StyledText ourConversation;
    private Text ourQuestion;
    private Button ourAskButton;
    private Label ourStatusLabel;
    private Label ourModeLabel;

    private final List<StyleRange> ourStyleRanges = new ArrayList<>();
    private Color ourUserBubble;
    private Color ourAnswerBubble;
    private Color ourErrorBubble;

    private String ourAttachedContext = "";
    private String ourLastAnswer = "";
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
    }

    /**
     * @return the top-level control to place inside the tab
     */
    public Control getControl() {
        return ourConversation;
    }

    /** Puts focus into the question box. */
    public void setFocus() {
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
        ourQuestion.setText(theQuestion);
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
        sendQuestion();
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
    public void reset() {
        ourSession.reset();
        if(!ourConversation.isDisposed()) {
            ourConversation.setText("");
        }
        ourStyleRanges.clear();
        ourLastAnswer = "";
        setStatus("Диалог сброшен");
    }

    /** Releases the SWT controls. */
    public void dispose() {
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.dispose();
        }
        for(final Color color : new Color[] {ourUserBubble, ourAnswerBubble,
            ourErrorBubble}) {
            if(color != null && !color.isDisposed()) {
                color.dispose();
            }
        }
    }

    private void createControls(final Composite theParent) {
        final Composite root = new Composite(theParent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        ourConversation = new StyledText(root,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        ourConversation.setMargins(6, 6, 6, 2);
        ourConversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        initBubbleColors();

        final Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        final GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        header.setLayout(headerLayout);
        final Button resetButton = new Button(header, SWT.PUSH);
        resetButton.setText("Новый диалог");
        resetButton.addListener(SWT.Selection, event -> reset());
        ourModeLabel = new Label(header, SWT.NONE);
        updateModeIndicator();

        final Composite input = new Composite(root, SWT.NONE);
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
                sendQuestion();
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
        ourAskButton.addListener(SWT.Selection, event -> sendQuestion());

        ourStatusLabel = new Label(root, SWT.NONE);
        ourStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    }

    private void sendQuestion() {
        final String question = ourQuestion.getText().trim();
        if(question.isEmpty() || ourBusy) {
            return;
        }
        final String context = ourAttachedContext;
        ourAttachedContext = "";
        final boolean editMode = AgentMode.isOn() && ourToolSupplier != null;
        if(!context.isEmpty()) {
            appendLine("Вы: " + question + "  [к вопросу прикреплён контекст проекта, "
                + context.length() + " символов, остаётся в диалоге до «Нового диалога»]");
        } else if(editMode) {
            appendLine("Вы: " + question + "  [режим правки: агент с инструментами проекта]");
        } else {
            appendLine("Вы: " + question + "  [контекст проекта: автоматический поиск]");
        }
        ourQuestion.setText("");
        ourBusy = true;
        ourAskButton.setEnabled(false);
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
                    appendLine("Colleague: " + body);
                    if(ourAnswerListener != null) {
                        ourAnswerListener.accept(body);
                    }
                });
                setStatus(stats);
                return Status.OK_STATUS;
            } catch (final Exception ex) {
                Display.getDefault().asyncExec(() -> appendLine("Ошибка: " + ex.getMessage()));
                return new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID, "assistant call failed", ex);
            } finally {
                ourBusy = false;
                Display.getDefault().asyncExec(() -> {
                    if(!ourAskButton.isDisposed()) {
                        ourAskButton.setEnabled(true);
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
            updateModeIndicator();
            setStatus(on ? "Режим правки ВКЛЮЧЁН — два ESC выключат" : "Режим правки ВЫКЛЮЧЕН");
        } else {
            ourLastEscapeAt = now;
        }
    }

    private void updateModeIndicator() {
        if(ourModeLabel == null || ourModeLabel.isDisposed()) {
            return;
        }
        ourModeLabel.setText(AgentMode.isOn()
            ? "\u2699 режим правки: вкл (код можно менять)" : "\u2699 режим правки: выкл");
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

    private void appendLine(final String theText) {
        if(ourConversation.isDisposed()) {
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
