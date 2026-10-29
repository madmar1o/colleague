package com.mad.edt.colleague.ui.internal;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
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
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.AgentMemory;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.agent.LoggingAgentObserver;
import com.mad.edt.colleague.agent.MemoryTool;
import com.mad.edt.colleague.agent.PlanBoard;
import com.mad.edt.colleague.agent.PlanTool;
import com.mad.edt.colleague.agent.ToolUsageStats;
import com.mad.edt.colleague.agent.subagent.SubagentRegistry;
import com.mad.edt.colleague.agent.subagent.SubagentTool;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.agents.ToolPolicy;
import com.mad.edt.colleague.chat.ContextBudget;
import com.mad.edt.colleague.chat.EscapeSequence;
import com.mad.edt.colleague.chat.PageCatalogEvents;
import com.mad.edt.colleague.chat.PageCommandHandler;
import com.mad.edt.colleague.chat.StreamBatcher;
import com.mad.edt.colleague.chat.TurnController;
import com.mad.edt.colleague.commands.CommandDefinition;
import com.mad.edt.colleague.commands.SlashCommands;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.GeneratorGate;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.mcp.McpCatalog;
import com.mad.edt.colleague.skills.SkillRegistry;
import com.mad.edt.colleague.skills.SkillShowcase;
import com.mad.edt.colleague.skills.SkillTool;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * One chat conversation tab bound to a dedicated {@link ChatSession}.
 * <p>
 * The conversation is rendered by the bundled {@code web/chat.html} page
 * inside an embedded browser: bubbles, markdown answers and the composer are
 * drawn by the page, the Java side receives bridge calls ({@code send},
 * {@code newChat}, {@code toggleEdit}, ...) and pushes events back through
 * {@code window.colleaguePush(kind, json)}. The page palette is derived from
 * the workbench theme, so the chat follows the light or dark skin chosen in
 * EDT. When no browser backend can be created the panel falls back to a
 * plain styled-text conversation so the feature keeps working everywhere.
 */
public final class ChatPanel {

    /** Model round-trips allowed for one edit-mode question. */
    private static final int AGENT_MAX_STEPS = 8;
    /**
     * Finished runs an offered tool may go uncalled before it leaves the
     * advertised catalog; a call inside a run resets the streak, so a pruned
     * name returns the moment the model wants it again.
     */
    private static final int PRUNE_AFTER_IDLE_RUNS = 3;
    /**
     * Preloaded context an agent turn may carry: the loop has its own search
     * tools, so a big dump only buys prompt processing and cut-off rounds.
     */
    private static final int AGENT_CONTEXT_MAX_CHARS = 3_000;
    // streaming policy lives with StreamBatcher itself (DEFAULT_MAX_BATCH_CHARS
    // and DEFAULT_MIN_INTERVAL_MS) so the tests pin the numbers the page runs on

    /** Max gap between two ESC presses that toggles the edit mode. */
    private static final long ESC_WINDOW_MS = 2_000L;
    /** Same physical ESC seen through both channels counts once inside this pause. */
    private static final long ESC_DEDUP_MS = 300L;

    /** Live panels, kept in sync when the process-wide edit mode changes. */
    private static final List<ChatPanel> OUR_PANELS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ChatSession ourSession;
    private final String ourModel;

    private Composite ourRoot;
    private BridgeBrowser ourBridge;
    private Control ourThemeAnchor;
    private Color ourPageBackground;

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
    private boolean ourPageProofDone;
    private final TurnController ourTurns = new TurnController();
    private Job ourQuestionJob;
    /** one-shot warm-up cycle runner, rescheduled while the panel lives */
    private volatile Job ourWarmthJob;
    private Listener ourEscFilter;
    private Consumer<String> ourAnswerListener;
    private Runnable ourOnBack;
    private Supplier<List<IToolExecutor>> ourToolSupplier;

    /**
     * Project-local skill catalog for the agent path, {@code null} when the
     * workspace carries no skills; consulted every turn so an empty catalog
     * adds neither the load_skill tool nor the catalog block.
     */
    private Supplier<SkillRegistry> ourSkillSupplier;

    /**
     * Project-local slash commands the user can type in the chat line,
     * {@code null} when the workspace carries none; consulted before every
     * send, so a matching line is expanded into its prompt template while a
     * plain or unknown line stays exactly as typed.
     */
    private Supplier<SlashCommands> ourCommandSupplier;

    /**
     * Agent profiles of the workspace: the profile chip lists them and the
     * picked one decides which tools the edit-mode agent is offered.
     */
    private Supplier<List<AgentProfile>> ourProfileSupplier;

    /**
     * Profile picked through the chip in this panel, empty for the default
     * agent; only meaningful while the edit mode runs the agent loop.
     */
    private String ourProfileName = "";

    /**
     * Delegates this chat keeps alive across its turns: calling the same
     * agent name again continues that delegate's own conversation instead of
     * starting from zero. Bounded, see {@link SubagentRegistry}.
     */
    private final SubagentRegistry ourSubagents = new SubagentRegistry();

    /**
     * Call counter of this chat across its edit-mode runs: feeds the pruning
     * of never-called tools out of the advertised catalog, see
     * {@link ToolUsageStats} and {@link #PRUNE_AFTER_IDLE_RUNS}.
     */
    private final ToolUsageStats ourToolUsage = new ToolUsageStats();

    /**
     * Remote tool catalog shared by the workbench session, {@code null} when
     * no MCP servers were ever configured; each edit run asks it for the
     * current adapters, see {@link McpCatalog}.
     */
    private McpCatalog ourMcpCatalog;

    /**
     * The step plan this chat keeps visible across its turns: the model
     * replaces it through the update_plan tool, and an unfinished board is
     * carried into the next task so a long job survives turn boundaries.
     */
    private final PlanBoard ourPlanBoard = new PlanBoard();

    /**
     * Shared lesson memory of the workbench session, {@code null} when none
     * was handed over; the remember tool writes into it and its lessons join
     * the agent instructions.
     */
    private AgentMemory ourLessonMemory;
    private Function<String, String> ourAutoContextSupplier;
    private final EscapeSequence ourEscGesture =
        new EscapeSequence(ESC_WINDOW_MS, ESC_DEDUP_MS);
    private String ourLastPalette = "";
    private String ourLastPageState = "";
    private final PageCommandHandler ourPageCommands =
        new PageCommandHandler(new BridgeActions());

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
        startRuntimeWarmth();
    }

    /**
     * @return the top-level control to place inside the tab
     */
    public Control getControl() {
        return ourRoot;
    }

    /** Puts focus into the question box. */
    public void setFocus() {
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            ourBridge.getControl().setFocus();
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
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("text", theQuestion);
            pushNow("input", payload);
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
     * @param theOnBack invoked on the UI thread when the page back arrow is
     *            pressed; the view switches to the contacts page
     */
    public void setOnBack(final Runnable theOnBack) {
        ourOnBack = theOnBack;
    }

    /**
     * @param theToolSupplier workspace tools for the agent loop, consulted at
     *            each question; {@code null} disables the agent path entirely
     */
    public void setToolSupplier(final Supplier<List<IToolExecutor>> theToolSupplier) {
        ourToolSupplier = theToolSupplier;
    }

    /**
     * @param theMcpCatalog remote tool catalog shared by the workbench
     *            session, {@code null} to offer no remote tools; the same
     *            instance across panels keeps one set of open server channels
     */
    public void setMcpCatalog(final McpCatalog theMcpCatalog) {
        ourMcpCatalog = theMcpCatalog;
    }

    /**
     * @param theSkillSupplier project-local skills advertised to the agent as
     *            a catalog plus the load_skill tool, consulted at each
     *            question; {@code null} or an empty catalog runs the agent
     *            without the skill layer
     */
    public void setSkillSupplier(final Supplier<SkillRegistry> theSkillSupplier) {
        ourSkillSupplier = theSkillSupplier;
    }

    /**
     * @param theCommandSupplier slash commands loaded from the workspace,
     *            consulted before every send; {@code null} or an empty
     *            catalog leaves slash lines as ordinary chat text
     */
    public void setCommandSupplier(final Supplier<SlashCommands> theCommandSupplier) {
        ourCommandSupplier = theCommandSupplier;
    }

    /**
     * @param theProfileSupplier agent profiles loaded from the workspace, used
     *            for the profile chip and for the per-profile tool policy in
     *            the edit mode; {@code null} leaves the chat without profiles
     */
    public void setProfileSupplier(final Supplier<List<AgentProfile>> theProfileSupplier) {
        ourProfileSupplier = theProfileSupplier;
    }

    /**
     * @param theLessonMemory cross-session lesson memory shared by all chats
     *            of the workbench session, {@code null} for none
     */
    public void setLessonMemory(final AgentMemory theLessonMemory) {
        ourLessonMemory = theLessonMemory;
    }

    /**
     * @param theSupplier takes the question text and produces search snippets
     *            for it when no context was attached manually; runs on a
     *            background thread
     */
    public void setAutoContextSupplier(final Function<String, String> theSupplier) {
        ourAutoContextSupplier = theSupplier;
    }

    /**
     * @return the conversation session this panel talks through; the view
     *         consults it when a provider's saved configuration moves and a
     *         tab would otherwise keep asking the endpoint the user corrected
     */
    public ChatSession getSession() {
        return ourSession;
    }

    /** Clears the conversation and the stored session history. */
    private void reset() {
        ourSession.reset();
        ourLastAnswer = "";
        if(ourBridge != null) {
            pushHistory();
            setStatus("Диалог сброшен");
            return;
        }
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.setText("");
        }
        ourStyleRanges.clear();
        setStatus("Диалог сброшен");
    }

    /**
     * Keeps a local runtime warm while this panel is open: the first pass
     * loads the model, later passes renew its residence claim before the
     * claim can lapse, so even the first question after a long pause starts
     * warm instead of paying the model load. Endpoints that never evict
     * models (gateway-style) report no claim and skip the whole cycle.
     */
    private void startRuntimeWarmth() {
        final ILLMProvider provider = ourSession.getProvider();
        final long renewalMs = provider.getConfig().warmRenewalDelayMs();
        if(renewalMs <= 0) {
            return;
        }
        scheduleWarmth(provider, renewalMs, 0L);
    }

    /**
     * Runs one warm-up pass in the background, then queues the next one to
     * go out before the residence claim of the previous call expires.
     *
     * @param theProvider endpoint to warm
     * @param theRenewalMs pause between two passes
     * @param theDelayMs when the pass must start
     */
    private void scheduleWarmth(final ILLMProvider theProvider, final long theRenewalMs,
        final long theDelayMs) {
        final IJobFunction warmthBody = (final IProgressMonitor monitor) -> {
            try {
                theProvider.warmModel(ourModel);
            } catch (final Exception ex) {
                System.err.println("[colleague] model warm-up failed: " + ex.getMessage());
            }
            if(!ourRoot.isDisposed()) {
                scheduleWarmth(theProvider, theRenewalMs, theRenewalMs);
            }
            return Status.OK_STATUS;
        };
        final Job warmth = Job.create("Colleague: model warm-up", warmthBody);
        warmth.setSystem(true);
        ourWarmthJob = warmth;
        warmth.schedule(theDelayMs);
    }

    private void createControls(final Composite theParent) {
        ourRoot = new Composite(theParent, SWT.NONE);
        ourRoot.setLayout(new GridLayout(1, false));
        ourRoot.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ourRoot.addListener(SWT.Dispose, event -> {
            OUR_PANELS.remove(ChatPanel.this);
            final Job warmth = ourWarmthJob;
            if(warmth != null) {
                warmth.cancel();
            }
        });
        if(createBrowser()) {
            return;
        }
        createFallback(ourRoot);
    }

    /**
     * Tries the embedded browser with the bundled chat page.
     *
     * @return {@code true} when the browser path is active
     */
    private boolean createBrowser() {
        try {
            ViewBridge.ensureUserDataDir();
            ourBridge = new BridgeBrowser(ourRoot, "web/chat.html", this::handleBridge);
            postEvent("ready", null);
            watchTheme();
            traceGeometry();
            installEscapeFilter();
            return true;
        } catch (final RuntimeException | LinkageError | org.eclipse.swt.SWTError ex) {
            System.err.println("colleague: embedded browser unavailable, plain fallback: "
                + ex.getMessage());
            ourBridge = null;
        }
        if(ourRoot.getChildren().length != 1) {
            for(final Control child : ourRoot.getChildren()) {
                child.dispose();
            }
            return false;
        }
        return true;
    }

    /** Logs where the chat browser sits on screen and who receives SWT input. */
    private void traceGeometry() {
        if(!Boolean.getBoolean("colleague.browser.selftest")) {
            return;
        }
        final Control control = ourBridge.getControl();
        control.addListener(SWT.MouseDown, event -> System.out.println(
            "[colleague] browser SWT mousedown x=" + event.x + " y=" + event.y));
        control.addListener(SWT.FocusIn,
            event -> System.out.println("[colleague] browser SWT focusin"));
        control.addListener(SWT.FocusOut,
            event -> System.out.println("[colleague] browser SWT focusout"));
        Display.getDefault().timerExec(8000, () -> {
            if(ourRoot.isDisposed() || control.isDisposed()) {
                return;
            }
            final org.eclipse.swt.graphics.Point topLeft = control.toDisplay(0, 0);
            final org.eclipse.swt.graphics.Point bottom =
                control.toDisplay(control.getSize().x, control.getSize().y);
            final org.eclipse.swt.graphics.Point abs =
                control.toDisplay(control.getLocation());
            System.out.println("[colleague] browser screen rect x=" + topLeft.x
                + " y=" + topLeft.y + " .. " + bottom.x + "," + bottom.y
                + " locInParent=" + abs.x + "," + abs.y
                + " size=" + control.getSize().x + "x" + control.getSize().y
                + " visible=" + control.isVisible() + " focused=" + control.isFocusControl());
        });
    }

    /**
     * Catches ESC on the SWT level: whenever the workbench focus sits anywhere
     * inside this panel (including the embedded browser and its DOM elements),
     * the double-ESC edit-mode gesture works even if the keystroke never
     * reaches the page's own script.
     */
    private void installEscapeFilter() {
        final Display display = ourRoot.getDisplay();
        ourEscFilter = (theEvent) -> {
            if(theEvent.keyCode != SWT.ESC || ourRoot.isDisposed()) {
                return;
            }
            Control widget = theEvent.widget instanceof Control ? (Control)theEvent.widget : null;
            while(widget != null && widget != ourRoot) {
                widget = widget.getParent();
            }
            if(widget == ourRoot) {
                registerEscape();
            }
        };
        display.addFilter(SWT.KeyDown, ourEscFilter);
        ourRoot.addListener(SWT.Dispose, event -> {
            if(ourEscFilter != null) {
                display.removeFilter(SWT.KeyDown, ourEscFilter);
                ourEscFilter = null;
            }
        });
    }

    private void handleBridge(final String theMethod, final JsonObject theArguments) {
        // the routing table itself lives in the tested PageCommandHandler;
        // here the call only becomes readable: page name-value pairs become
        // plain strings for the handler
        ourPageCommands.handle(theMethod, name -> bridgeArgument(theArguments, name));
    }

    private static String bridgeArgument(final JsonObject theArguments, final String theName) {
        return theArguments == null ? "" : BridgeBrowser.optString(theArguments, theName);
    }

    /**
     * Panel-side effects behind the page bridge: status line, SWT clipboard,
     * the question job and the escape filter. Routing which method triggers
     * which effect is pinned by the PageCommandHandler tests.
     */
    private final class BridgeActions implements PageCommandHandler.Actions {

        @Override
        public void bridgeReady() {
            pushHistory();
            pushCatalogEvent();
            setStatus(AgentMode.isOn()
                ? "Режим правки ВКЛЮЧЁН — агент с инструментами проекта"
                : "Готов к работе");
            schedulePageSendProof();
        }

        @Override
        public void pageStateChanged(final String theState) {
            if(!theState.equals(ourLastPageState)) {
                ourLastPageState = theState;
                System.out.println("[colleague] page focus state: " + theState);
            }
        }

        @Override
        public void send(final String theText) {
            if(Boolean.getBoolean("colleague.browser.selftest")) {
                System.out.println("[colleague] bridge: page reached the panel's "
                    + "input handler");
            }
            sendQuestion(theText);
        }

        @Override
        public void newChat() {
            reset();
        }

        @Override
        public void toggleEdit() {
            ChatPanel.this.toggleEdit();
        }

        @Override
        public void copy(final String theText) {
            ViewBridge.copyToClipboard(ourRoot, theText);
        }

        @Override
        public void attachFile() {
            setStatus("Файлы пока не прикрепляются — используйте контекст проекта из поиска");
        }

        @Override
        public void back() {
            if(ourOnBack != null) {
                ourOnBack.run();
            }
        }

        @Override
        public void escape() {
            registerEscape();
        }

        @Override
        public void abort() {
            stopGeneration();
        }

        @Override
        public void diagnose(final String theText) {
            // page-side measurements for layout regression proofs; only the
            // selftest scripts ever call this, keep the noise out of normal runs
            if(Boolean.getBoolean("colleague.browser.selftest")) {
                System.out.println("[colleague] page diag: " + theText);
            }
        }

        @Override
        public void pickProfile(final String theName) {
            pickProfileName(theName);
        }
    }

    /** @return {@code true} when the chat page announced itself on the bridge */
    public boolean isBridgeReady() {
        return ourBridge != null && ourBridge.isReady();
    }

    /**
     * Debug-only proof that the round trip works with a real user gesture:
     * once the page is attached, the page itself fills its input and submits
     * its own form, exactly as clicking the send button would. The question
     * then arrives back in Java through the page's call channel.
     */
    private void schedulePageSendProof() {
        if(!Boolean.getBoolean("colleague.browser.selftest") || ourBridge == null
            || ourPageProofDone) {
            return;
        }
        ourPageProofDone = true;
        final boolean abortProof = Boolean.getBoolean("colleague.browser.abortproof");
        Display.getDefault().timerExec(12000, () -> {
            if(ourRoot.isDisposed()) {
                return;
            }
            final String question = abortProof
                ? "proof: write a very long detailed essay about typical configuration"
                    + " architecture problems, at least two thousand words"
                : "proof: please answer with one word";
            final String script =
                "var q=document.getElementById('q');"
                + "if(q && !q.value){q.value='" + question + "';"
                + "q.dispatchEvent(new Event('input'));"
                + "var f=document.getElementById('composer');"
                + "if(f.requestSubmit){f.requestSubmit();}else{"
                + "f.dispatchEvent(new Event('submit',{cancelable:true,bubbles:true}));}}";
            ourBridge.executeScript(script);
            System.out.println("[colleague] bridge: page-side gesture injected");
            if(!abortProof) {
                // layout regression probe: the question above produces a one-word
                // answer, i.e. exactly the narrow bubble where the absolutely
                // positioned time line used to hang out of the bubble and cover
                // the avatar; measure every incoming bubble and report through
                // the bridge so the log shows the geometry numbers
                Display.getDefault().timerExec(35000, () -> {
                    if(ourRoot.isDisposed() || ourBridge == null) {
                        return;
                    }
                    System.out.println("[colleague] layout probe: measuring bubbles");
                    ourBridge.executeScript(
                        "var out=[];"
                        + "document.querySelectorAll('.row.in .bubble')"
                        + ".forEach(function(b){"
                        + "var m=b.querySelector('.meta');"
                        + "var av=b.parentNode.querySelector('.smallavatar');"
                        + "if(!m||!av){return;}"
                        + "var mr=m.getBoundingClientRect(),"
                        + "br=b.getBoundingClientRect(),"
                        + "ar=av.getBoundingClientRect();"
                        + "out.push('bw='+Math.round(br.width)"
                        + "+' metaLeft='+Math.round(mr.left)"
                        + "+' bubbleLeft='+Math.round(br.left)"
                        + "+' avatarRight='+Math.round(ar.right)"
                        + "+' avatarOverlapsMeta='"
                        + "+String(ar.right>mr.left));});"
                        + "apiCall('diag',{txt:out.join(' | ')});");
                });
            }
            if(abortProof) {
                // autonomous proof of the stop path: six seconds into a long
                // generation the panel dispatches the very stop request the
                // page button would send, then measures how quickly the
                // provider loop notices it
                Display.getDefault().timerExec(6000, () -> {
                    if(ourRoot.isDisposed()) {
                        return;
                    }
                    System.out.println("[colleague] abort proof: dispatching stop");
                    handleBridge("abort", new JsonObject());
                });
                Display.getDefault().timerExec(8000, () -> {
                    if(ourRoot.isDisposed()) {
                        return;
                    }
                    // ESC twice while the turn is still wrapping up: the page
                    // must route both presses and the panel must flip mode
                    System.out.println("[colleague] abort proof: injecting ESC pair");
                    ourBridge.executeScript(
                        "document.dispatchEvent(new KeyboardEvent('keydown',"
                        + "{key:'Escape',bubbles:true}));");
                });
                Display.getDefault().timerExec(8600, () -> {
                    if(ourRoot.isDisposed()) {
                        return;
                    }
                    ourBridge.executeScript(
                        "document.dispatchEvent(new KeyboardEvent('keydown',"
                        + "{key:'Escape',bubbles:true}));");
                });
                Display.getDefault().timerExec(9500, () -> {
                    if(ourRoot.isDisposed()) {
                        return;
                    }
                    // second question through the page composer while the workbench
                    // is still busy: proves the input is not locked after a stop
                    System.out.println("[colleague] abort proof: injecting second question");
                    ourBridge.executeScript(
                        "var q=document.getElementById('q');"
                        + "q.value='proof2: answer with one word';"
                        + "q.dispatchEvent(new Event('input'));"
                        + "var f=document.getElementById('composer');"
                        + "if(f.requestSubmit){f.requestSubmit();}else{"
                        + "f.dispatchEvent(new Event('submit',{cancelable:true,"
                        + "bubbles:true}));}");
                });
                Display.getDefault().timerExec(15000, () -> {
                    if(ourRoot.isDisposed()) {
                        return;
                    }
                    // stop the second turn too: it runs through the agent loop,
                    // so this exercises cancellation between agent steps
                    System.out.println("[colleague] abort proof: stopping second turn");
                    handleBridge("abort", new JsonObject());
                });
            }
        });
    }

    private void toggleEdit() {
        final boolean on = AgentMode.toggle();
        refreshModeIndicators();
        setStatus(on
            ? "Режим правки ВКЛЮЧЁН — агент с инструментами проекта"
            : "Режим правки ВЫКЛЮЧЕН");
    }

    private void postEvent(final String theKind, final JsonObject thePayload) {
        final JsonObject payload = thePayload == null ? new JsonObject() : thePayload;
        if("ready".equals(theKind)) {
            final JsonObject palette = ThemeSupport.pagePalette(
                ourThemeAnchor != null && !ourThemeAnchor.isDisposed()
                    ? ourThemeAnchor : ourRoot);
            ourLastPalette = palette.toString();
            applyNativePalette(palette);
            payload.add("palette", palette);
            payload.addProperty("name", "Colleague");
            payload.addProperty("sub", ourModel == null || ourModel.isEmpty()
                ? "ассистент по проекту" : ourModel);
            payload.addProperty("mode", Boolean.valueOf(AgentMode.isOn()));
        }
        ourBridge.push(theKind, payload);
    }

    /**
     * Queues one event and asks the bridge to deliver it right away, so the
     * page sees it within a UI tick instead of at the next sync poll. The
     * polling reply stays as the fallback for the moment the script channel
     * is not warm yet.
     *
     * @param theKind event name seen by the page
     * @param thePayload JSON payload of the event
     */
    private void pushNow(final String theKind, final JsonObject thePayload) {
        ourBridge.push(theKind, thePayload);
        // the queue is the single source of truth for delivery, so the batch
        // argument stays empty; pushEvents drains everything queued before it
        ourBridge.pushEvents(java.util.Collections.<Object[]>emptyList());
    }

    /**
     * Paints the panel container itself with the page background color so no
     * neutral-colored strip remains around the HTML page.
     */
    private void applyNativePalette(final JsonObject thePalette) {
        if(ourRoot.isDisposed()) {
            return;
        }
        final Color fresh = ThemeSupport.pageColor(ourRoot.getDisplay(), thePalette, "bg");
        if(ourPageBackground != null && !ourPageBackground.isDisposed()) {
            ourPageBackground.dispose();
        }
        ourPageBackground = fresh;
        ourRoot.setBackground(fresh);
    }

    /**
     * Names the workbench control whose themed colors define the page palette.
     *
     * @param theAnchor a control directly colored by the workbench theme
     */
    public void attachThemeAnchor(final Control theAnchor) {
        ourThemeAnchor = theAnchor;
        if(ourBridge != null && ourBridge.isReady() && !ourRoot.isDisposed()) {
            final JsonObject palette = ThemeSupport.pagePalette(theAnchor);
            ourLastPalette = palette.toString();
            applyNativePalette(palette);
            pushNow("readyPalette", palette);
        }
    }

    /**
     * Watches for workbench theme color changes while the browser is alive and
     * re-pushes the page palette whenever the derived colors actually differ.
     */
    private void watchTheme() {
        ThemeSupport.watchPalette(ourRoot, palette -> {
            if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
                ourLastPalette = palette.toString();
                applyNativePalette(palette);
                pushNow("readyPalette", palette);
            }
        });
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
        pushNow("history", payload);
    }

    /**
     * Advertises the workspace catalog to the page when the tab opens: slash
     * commands, project skills and (once they exist) agent profiles, so the
     * composer can hint command names and the page can list what is installed
     * without asking the user to guess. An empty catalog still pushes the
     * event, the page then simply shows nothing extra.
     */
    private void pushCatalogEvent() {
        if(ourBridge == null || !ourBridge.isReady()) {
            return;
        }
        final SlashCommands commands =
            ourCommandSupplier == null ? null : ourCommandSupplier.get();
        final SkillRegistry skills =
            ourSkillSupplier == null ? null : ourSkillSupplier.get();
        pushNow("catalog", PageCatalogEvents.build(
            commands == null ? List.of() : commands.catalog(), skills, profileCatalog()));
    }

    /**
     * @return agent profiles of the workspace, empty when none are loaded
     */
    private List<AgentProfile> profileCatalog() {
        final List<AgentProfile> profiles =
            ourProfileSupplier == null ? null : ourProfileSupplier.get();
        return profiles == null ? List.of() : profiles;
    }

    /**
     * Resolves the profile picked through the chip: an unknown or sub-agent
     * only name falls back to the default agent, so a stale chip state can
     * never run a helper profile as the chat itself.
     *
     * @return the picked profile, {@code null} for the default agent
     */
    private AgentProfile pickedProfile() {
        if(ourProfileName.isEmpty()) {
            return null;
        }
        for(final AgentProfile profile : profileCatalog()) {
            if(profile.getName().equals(ourProfileName)
                && profile.getMode() != AgentProfile.Mode.SUBAGENT) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Switches the chat to one of the workspace profiles and tells the page
     * what the agent will get under it.
     *
     * @param theName profile name picked by the chip, empty for the default agent
     */
    private void pickProfileName(final String theName) {
        ourProfileName = theName == null ? "" : theName.trim();
        final AgentProfile profile = pickedProfile();
        if(profile == null) {
            ourProfileName = "";
            setStatus("Профиль: обычный ассистент");
            return;
        }
        final StringBuilder note = new StringBuilder("Профиль: ").append(profile.getName());
        if(!profile.getToolNames().isEmpty()) {
            note.append(", инструменты: ").append(String.join(", ", profile.getToolNames()));
        }
        if(profile.getMaxSteps() > 0) {
            note.append(", шагов: ").append(profile.getMaxSteps());
        }
        setStatus(note.toString());
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
        // a slash line naming a loaded command is expanded into its prompt
        // template before the model sees it; the chat bubble keeps showing
        // what the user actually typed, an unknown slash line stays text
        final SlashCommands commands =
            ourCommandSupplier == null ? null : ourCommandSupplier.get();
        final SlashCommands.Invocation invocation =
            commands == null ? null : commands.match(question);
        final String prompt = invocation == null ? question : invocation.prompt();
        final String context = ourAttachedContext;
        ourAttachedContext = "";
        final boolean editMode = AgentMode.isOn() && ourToolSupplier != null;
        final boolean browser = ourBridge != null && ourBridge.isReady();
        if(browser) {
            final JsonObject user = new JsonObject();
            user.addProperty("text", question);
            user.addProperty("time", timeLabel());
            pushNow("user", user);
            final JsonObject busy = new JsonObject();
            busy.addProperty("busy", Boolean.TRUE);
            pushNow("busy", busy);
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
        final TurnController.Turn turn = ourTurns.startTurn();
        if(ourAskButton != null && !ourAskButton.isDisposed()) {
            ourAskButton.setEnabled(false);
        }
        setStatus(editMode ? "Агент: ищу по проекту и исполняю инструменты..." : "Думаю...");

        final StreamBatcher deltaBatcher = new StreamBatcher();
        final StreamBatcher thinkingBatcher = new StreamBatcher();

        final IJobFunction questionBody = (final IProgressMonitor monitor) -> {
            // the turn owns the generator from context gathering to the last
            // streamed chunk: a background embedding pass interleaving in
            // between swaps the single resident model and every step of this
            // turn pays for loading it back
            GeneratorGate.GLOBAL.enter();
            try {
                ourTurns.attachWorker(turn, Thread.currentThread());
                final String effectiveContext = context.isEmpty() && ourAutoContextSupplier != null
                    ? applyOrEmpty(ourAutoContextSupplier, prompt) : context;
                System.err.println("[colleague] autocontext: " + effectiveContext.length()
                    + " chars for question [" + question.replace('\n', ' ') + "]");
                final String answer;
                if(editMode) {
                    answer = runAgent(prompt,
                        ContextBudget.trimMiddle(effectiveContext, AGENT_CONTEXT_MAX_CHARS),
                        turn.cancellation());
                } else {
                    answer = ourSession.ask(ourModel, effectiveContext, prompt,
                        browser ? chunk -> feedDelta(chunk, deltaBatcher)
                            : chunk -> { },
                        browser ? chunk -> feedDelta(chunk, thinkingBatcher,
                            "thinking")
                            : chunk -> { },
                        turn.cancellation());
                }
                final int split = answer.lastIndexOf("\n--- ");
                final String body = split > 0 ? answer.substring(0, split).strip() : answer;
                final String baseStats = split > 0 ? answer.substring(split + 1).strip() : "";
                // the runtime cuts the oldest prompt text in silence when the
                // window runs out: only the stop reason says so, and without
                // this note the user reads a shortened answer as a full one
                final String stats = ourSession.wasLastTurnTruncated()
                    ? (baseStats.isEmpty()
                        ? "Контекст обрезан рантаймом, ответ неполный"
                        : baseStats + " — контекст обрезан рантаймом, ответ неполный")
                    : baseStats;
                ourLastAnswer = body;
                if(turn.cancellation().isCancelled()) {
                    System.out.println("[colleague] cancelled turn observed: partial "
                        + body.length() + " chars, stop->unwind latency "
                        + ourTurns.stopLatencyMs() + " ms");
                }
                if(!body.isEmpty() && ourTurns.isCurrent(turn)) {
                    Display.getDefault().asyncExec(() -> {
                        if(browser) {
                            final JsonObject payload = new JsonObject();
                            payload.addProperty("text", body);
                            payload.addProperty("time", timeLabel());
                            pushNow("answer", payload);
                        } else {
                            appendLine("Colleague: " + body);
                        }
                        if(ourAnswerListener != null) {
                            ourAnswerListener.accept(body);
                        }
                    });
                }
                setStatus(stats);
                return Status.OK_STATUS;
            } catch (final Exception ex) {
                final boolean aborted = ourTurns.isAborted();
                if(aborted) {
                    System.out.println("[colleague] cancelled turn unwound with error: "
                        + ex.getMessage() + ", stop->unwind latency "
                        + ourTurns.stopLatencyMs() + " ms");
                }
                if(!aborted && ourTurns.isCurrent(turn)) {
                    Display.getDefault().asyncExec(() -> {
                        if(browser) {
                            final JsonObject payload = new JsonObject();
                            payload.addProperty("text", String.valueOf(ex.getMessage()));
                            payload.addProperty("time", timeLabel());
                            pushNow("error", payload);
                        } else {
                            appendLine("Ошибка: " + ex.getMessage());
                        }
                    });
                    return new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID,
                        "assistant call failed", ex);
                }
                return Status.OK_STATUS;
            } finally {
                GeneratorGate.GLOBAL.leave();
                if(Thread.currentThread().isInterrupted()) {
                    // the stop interrupt belongs to this turn only: clear its
                    // status so it cannot leak into the next pooled worker run
                    Thread.interrupted();
                }
                ourBusy = false;
                Display.getDefault().asyncExec(() -> {
                    if(ourAskButton != null && !ourAskButton.isDisposed()) {
                        ourAskButton.setEnabled(true);
                    }
                    if(ourBridge != null && ourBridge.isReady()) {
                        final JsonObject busy = new JsonObject();
                        busy.addProperty("busy", Boolean.FALSE);
                        pushNow("busy", busy);
                    }
                });
            }
        };
        ourQuestionJob = Job.create("Colleague: question", questionBody);
        ourQuestionJob.schedule();
    }

    /**
     * Queues one streamed answer chunk and forwards a batch to the chat page
     * at most a few times per second, so token-level deltas never flood the
     * bridge. Runs on the provider call thread; all grouping policy lives in
     * {@link StreamBatcher}, which the tests pin down directly.
     */
    private void feedDelta(final String theChunk, final StreamBatcher theBatcher) {
        feedDelta(theChunk, theBatcher, "delta");
    }

    /**
     * Same batching under an explicit event kind, used to keep the model's
     * reasoning stream apart from the answer stream.
     */
    private void feedDelta(final String theChunk, final StreamBatcher theBatcher,
        final String theKind) {
        if(ourBridge == null || ourRoot.isDisposed() || ourTurns.isAborted()) {
            // after a stop request no more streamed text goes to the page:
            // the partial text already flushed stays where it is
            return;
        }
        final String batch = theBatcher.accept(theChunk);
        if(batch == null) {
            return;
        }
        Display.getDefault().asyncExec(() -> {
            if(ourBridge != null && ourBridge.isReady() && !ourRoot.isDisposed()) {
                final JsonObject payload = new JsonObject();
                payload.addProperty("text", batch);
                pushNow(theKind, payload);
            }
        });
    }

    /**
     * Edit-mode path: the agent loop may call workspace tools (every write is
     * confirmed by the user) before it answers; the exchange is remembered in
     * the session history like a plain question.
     */
    private String runAgent(final String theQuestion, final String theContext,
        final CancellationFlag theCancellation)
        throws com.mad.edt.colleague.llm.LLMException {
        final AgentProfile profile = pickedProfile();
        final ToolPolicy policy = new ToolPolicy().excludeUnused(PRUNE_AFTER_IDLE_RUNS);
        final List<IToolExecutor> tools = new ArrayList<>();
        for(final IToolExecutor tool : ourToolSupplier.get()) {
            if(policy.allows(profile, tool.getName())
                && policy.shouldOffer(ourToolUsage, tool.getName())) {
                tools.add(tool);
            }
        }
        if(policy.allows(profile, "update_plan") && policy.shouldOffer(ourToolUsage, "update_plan")) {
            tools.add(new PlanTool(ourPlanBoard));
        }
        if(ourLessonMemory != null && policy.allows(profile, "remember")
            && policy.shouldOffer(ourToolUsage, "remember")) {
            tools.add(new MemoryTool(ourLessonMemory));
        }
        final SkillRegistry skills = ourSkillSupplier == null ? null : ourSkillSupplier.get();
        if(skills != null && !skills.isEmpty() && policy.allows(profile, "load_skill")
            && policy.shouldOffer(ourToolUsage, "load_skill")) {
            tools.add(new SkillTool(skills));
        }
        if(ourMcpCatalog != null) {
            // remote tools join after the local ones so a built-in name always
            // wins the collision check, and they reach the subagent delegate
            // through the same list below
            final Set<String> reserved = new LinkedHashSet<>();
            boolean localWrites = false;
            for(final IToolExecutor tool : tools) {
                reserved.add(tool.getName());
                localWrites |= "write_file".equals(tool.getName())
                    || "edit_file".equals(tool.getName());
            }
            for(final IToolExecutor remote
                : ourMcpCatalog.tools(reserved, this::confirmRemoteCall)) {
                if(!remote.isParallelSafe() && !localWrites) {
                    continue; // a read-only run keeps read-only remote tools only
                }
                if(policy.allows(profile, remote.getName())
                    && policy.shouldOffer(ourToolUsage, remote.getName())) {
                    tools.add(remote);
                }
            }
        }
        if(policy.allows(profile, "subagent") && policy.shouldOffer(ourToolUsage, "subagent")) {
            // the delegate hears about its finished turn through this panel so
            // the page shows a card; the listener thread is the worker thread,
            // delivery to the page goes through asyncExec as everywhere else
            // the delegate resolves its own profile model inside the tool, so
            // the parent hands over just the chat's model as the fallback
            tools.add(new SubagentTool(ourSession.getProvider(), ourModel, tools,
                profileCatalog(), 0, theCancellation, ourSubagents,
                (agent, description, result) -> Display.getDefault().asyncExec(() -> {
                    if(ourBridge != null && ourBridge.isReady() && !ourRoot.isDisposed()) {
                        final JsonObject card = new JsonObject();
                        card.addProperty("agent", agent);
                        card.addProperty("description", description);
                        card.addProperty("result", result);
                        pushNow("subagent", card);
                    }
                })));
        }
        final String model = profile != null && profile.getModelName() != null
            && !profile.getModelName().isEmpty() ? profile.getModelName() : ourModel;
        final int maxSteps = profile != null && profile.getMaxSteps() > 0
            ? profile.getMaxSteps() : AGENT_MAX_STEPS;
        final List<String> offered = new ArrayList<>();
        for(final IToolExecutor tool : tools) {
            offered.add(tool.getName());
        }
        // the counter sees the exact list this run advertises, so a name the
        // model keeps ignoring ages toward pruning while a fresh call
        // resets its streak the same run it happens
        ourToolUsage.noteOffered(offered);
        final AgentLoop loop = new AgentLoop(ourSession.getProvider(),
            tools, model, maxSteps, theCancellation,
            new LoggingAgentObserver(ourToolUsage));
        final StringBuilder system = new StringBuilder();
        if(ourSession.getSystemPrompt() != null && !ourSession.getSystemPrompt().isEmpty()) {
            system.append(ourSession.getSystemPrompt()).append(' ');
        }
        system.append("You can call the provided workspace tools (search_code, list_files,")
            .append(" read_file, describe_configuration, write_file, edit_file). Call a tool")
            .append(" only through the tool-call interface: never write a tool command or an")
            .append(" example of one inside the answer text. For any")
            .append(" question about the configuration itself - its name, version, what")
            .append(" objects it has - call describe_configuration first. To change an")
            .append(" existing file use edit_file: read it first, then copy fragments")
            .append(" verbatim so each search text is unique. Search the project before")
            .append(" answering code questions; quote paths and line numbers. Every change")
            .append(" is confirmed by the user. Keep your approach visible: before a")
            .append(" multi-step task call update_plan with the steps, and call it again")
            .append(" each time a step finishes. Answer in the language of the question.");
        if(profile != null && !profile.getSystem().isEmpty()) {
            // the profile body speaks for the agent: it arrives as extra
            // instructions on top of the shared workspace rules
            system.append(" Profile \"").append(profile.getName()).append("\": ")
                .append(profile.getSystem());
        }
        if(ourLessonMemory != null) {
            system.append(" When you learn something worth keeping for later sessions on")
                .append(" this project - a quirk, a rule the user corrected you on, an")
                .append(" approach that worked - call remember with one short sentence.");
        }
        if(ourLessonMemory != null && ourLessonMemory.lessonCount() > 0) {
            system.append(' ').append(ourLessonMemory.render());
        }
        if(skills != null && !skills.isEmpty()) {
            system.append(new SkillShowcase(skills).catalogSection());
        }
        if(policy.allows(profile, "subagent") && policy.shouldOffer(ourToolUsage, "subagent")) {
            system.append(" For a bounded research task that needs many searches and")
                .append(" reads, call subagent with a clear prompt: a delegate works the")
                .append(" task on its own with read-only tools and returns one final")
                .append(" answer; it cannot change files.");
        }
        String task = theQuestion;
        if(!theContext.isEmpty()) {
            task = task + "\n\nProject context found by search:\n" + theContext;
        }
        if(hasUnfinishedSteps()) {
            task = task + "\n\nPlan you left on the board in earlier turns:\n"
                + ourPlanBoard.render();
        }
        final long startedAt = System.nanoTime();
        final AgentAnswer answer;
        try {
            answer = loop.run(system.toString(), task);
        } finally {
            // the run is over whatever the outcome: offered-but-uncalled names
            // age here, and the pruning decision of the next run reads that
            ourToolUsage.finishRun();
        }
        if(!answer.getText().isEmpty()) {
            ourSession.noteExchange(theQuestion, answer.getText());
        }
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return answer.getText() + String.format(
            "%n--- режим правки: %d шагов, %d вызовов инструментов, %d мс",
            Integer.valueOf(answer.getSteps()), Integer.valueOf(answer.getExecutedToolCalls()),
            Long.valueOf(elapsedMs));
    }

    /**
     * Modal yes/no gate for one write-like remote call, asked on the UI
     * thread exactly like the workspace file tools ask before a write.
     *
     * @param theQuestion the full call description to show the user
     * @return {@code "yes"} when the user allowed the call, {@code "denied"}
     *         otherwise; a vanished shell denies so nothing hangs
     */
    private String confirmRemoteCall(final String theQuestion) {
        final Display display = ourRoot.isDisposed() ? null : ourRoot.getDisplay();
        if(display == null || display.isDisposed()) {
            return "denied";
        }
        final Boolean[] answer = new Boolean[1];
        display.syncExec(() -> {
            if(ourRoot.isDisposed() || ourRoot.getShell() == null) {
                answer[0] = Boolean.FALSE;
                return;
            }
            answer[0] = Boolean.valueOf(MessageDialog.openConfirm(ourRoot.getShell(),
                "Colleague: вызов удалённого инструмента", theQuestion));
        });
        return answer[0] != null && answer[0].booleanValue() ? "yes" : "denied";
    }

    /**
     * @return {@code true} when the board still carries steps the model has
     *         not finished, the only state worth carrying into the next task
     */
    private boolean hasUnfinishedSteps() {
        for(final PlanBoard.Step step : ourPlanBoard.steps()) {
            if(step.getStatus() != PlanBoard.Status.DONE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Single owner of the stop gesture for the in-flight turn: fires the
     * cancellation latch the providers poll between streamed frames and
     * interrupts the worker out of blocking waits such as the response
     * header phase. The page unlocks right away because the transcript
     * already shows what was produced; the worker unwinds in the
     * background without further visible effects.
     */
    private void stopGeneration() {
        if(!ourBusy || ourQuestionJob == null || ourQuestionJob.getState() == Job.NONE) {
            return;
        }
        ourTurns.abortTurn();
        ourQuestionJob.cancel();
        setStatus("Генерация остановлена");
        if(ourBridge != null && ourBridge.isReady()) {
            final JsonObject busy = new JsonObject();
            busy.addProperty("busy", Boolean.FALSE);
            pushNow("busy", busy);
        }
    }

    private void registerEscape() {
        if(ourBusy) {
            // mid-generation ESC means "stop the answer", never the edit
            // pair: arming write tools under a user who is trying to halt
            // generation is the one thing this gesture must not do
            System.out.println("[colleague] escape during generation: stopping"
                + " turn, edit mode untouched");
            stopGeneration();
            return;
        }
        switch(ourEscGesture.press()) {
            case IGNORED:
                // the same physical ESC can arrive twice: once through the SWT
                // key filter and once as a bridge call forwarded by the page
                return;
            case TOGGLED:
                System.out.println("[colleague] escape pair observed within window:"
                    + " toggling edit mode");
                toggleEdit();
                return;
            default:
                setStatus("Ещё раз ESC — переключить режим правки");
        }
    }

    /** Redraws the edit-mode indicator of every live panel. */
    private static void refreshModeIndicators() {
        for(final ChatPanel panel : OUR_PANELS) {
            panel.updateModeIndicator();
        }
    }

    /**
     * @return {@code true} while any live panel is busy answering a question,
     *         so background jobs can hold back and not compete with the
     *         conversation for the model runtime
     */
    public static boolean anyBusy() {
        for(final ChatPanel panel : OUR_PANELS) {
            if(panel.ourBusy) {
                return true;
            }
        }
        return false;
    }

    private void updateModeIndicator() {
        if(ourBridge != null && ourBridge.isReady()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("on", Boolean.valueOf(AgentMode.isOn()));
            pushNow("mode", payload);
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
        if(ourBridge != null && ourBridge.isReady()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("text", theText == null ? "" : theText);
            pushNow("status", payload);
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

    /** Releases the SWT controls. */
    public void dispose() {
        OUR_PANELS.remove(this);
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            ourBridge.getControl().dispose();
        }
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.dispose();
        }
        if(ourRoot != null && !ourRoot.isDisposed()) {
            ourRoot.dispose();
        }
        for(final Color color : new Color[] {ourUserBubble, ourAnswerBubble,
            ourErrorBubble, ourPageBackground}) {
            if(color != null && !color.isDisposed()) {
                color.dispose();
            }
        }
    }
}
