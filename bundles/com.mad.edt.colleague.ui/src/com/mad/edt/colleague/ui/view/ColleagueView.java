package com.mad.edt.colleague.ui.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.mad.edt.colleague.agent.AgentMemory;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.MetadataTools;
import com.mad.edt.colleague.agents.AgentCatalog;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.agents.AgentProfileWriter;
import com.mad.edt.colleague.chat.ContextBudget;
import com.mad.edt.colleague.context.CodeSearch;
import com.mad.edt.colleague.context.ConfigurationOverview;
import com.mad.edt.colleague.context.ContextSource;
import com.mad.edt.colleague.context.KeywordContextSource;
import com.mad.edt.colleague.context.SemanticIndex;
import com.mad.edt.colleague.context.SemanticSettings;
import com.mad.edt.colleague.commands.CommandDefinition;
import com.mad.edt.colleague.commands.CommandLoader;
import com.mad.edt.colleague.commands.SlashCommands;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.mcp.McpCatalog;
import com.mad.edt.colleague.skills.SkillDefinition;
import com.mad.edt.colleague.skills.SkillLoader;
import com.mad.edt.colleague.skills.SkillRegistry;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;
import com.mad.edt.colleague.ui.internal.ActiveEditorWatcher;
import com.mad.edt.colleague.ui.internal.AgentsPanel;
import com.mad.edt.colleague.ui.internal.ChatPanel;
import com.mad.edt.colleague.ui.internal.ChatSession;
import com.mad.edt.colleague.ui.internal.ChatSessionRegistry;
import com.mad.edt.colleague.ui.internal.ContactsPanel;
import com.mad.edt.colleague.ui.internal.PageSwitchNote;
import com.mad.edt.colleague.ui.internal.StartupDeferral;
import com.mad.edt.colleague.ui.internal.WorkspaceTools;

/**
 * Chat-messenger shell of the Colleague assistant: the agents page lists the
 * agents the user can talk to and opens a chat tab under the picked one; the
 * provider list lives behind the gear as a settings page only. Each contact
 * keeps its own conversation through the session registry, so closing and
 * reopening a tab preserves history while the view lives.
 */
public class ColleagueView extends ViewPart {

    /** View identifier used in extension declarations and handlers. */
    public static final String ID = "com.mad.edt.colleague.ui.view.ColleagueView";

    /** Pause before a retry semantic pass over the files the last one lost. */
    private static final long SEMANTIC_RETRY_DELAY_MS = 30_000L;

    /** Shortest wait while a chat turn holds the model runtime busy. */
    private static final long SEMANTIC_CHAT_WAIT_MS = 2_000L;

    /** Cap on semantic passes so dead endpoints cannot keep the loop alive forever. */
    private static final int SEMANTIC_MAX_PASSES = 4;

    private static final String SYSTEM_PROMPT =
        "You are a helpful assistant embedded in an IDE for the 1C:Enterprise platform."
            + " Answer concisely in the language of the question.";

    /** Profile file this plugin ships as the first agent of an empty catalog. */
    private static final String SEED_AGENT_RESOURCE = "seed/agents/analyst-1c.md";

    /** Id the shipped seed is written under; also its file name. */
    private static final String SEED_AGENT_ID = "analyst-1c";

    /** Question staged by handlers before the view instance exists. */
    private static volatile String ourPendingQuestion;

    /** Resource/project context staged together with the question. */
    private static volatile String ourPendingContext;

    /** The single visible instance (the view is not multiplicable). */
    private static volatile ColleagueView ourInstance;

    /** Conversations keyed by contact; rebuilt when a saved endpoint moves. */
    private final ChatSessionRegistry ourSessions = new ChatSessionRegistry();
    private final Map<String, ChatPanel> ourPanels = new HashMap<>();

    private Composite ourRoot;
    private Composite ourAgentsArea;
    private AgentsPanel ourAgents;
    private Composite ourContactsArea;
    private Composite ourTabsArea;
    private Composite ourMobileStack;
    private StackLayout ourMobileStackLayout;
    private ContactsPanel ourContacts;
    private CTabFolder ourTabFolder;
    private CodeSearch ourCodeSearch;
    private ConfigurationOverview ourOverview;
    /** Metadata tools of the open projects, rebuilt from the workspace per turn. */
    private final MetadataTools ourMetadataTools = new MetadataTools(this::metadataRoots);

    /**
     * Remote tool catalog shared by every chat tab of this view: one set of
     * open MCP server channels for the whole workbench session, rebuilt when
     * the settings page changes the server list.
     */
    private final McpCatalog ourMcpCatalog = McpCatalog.live();

    /**
     * Lesson memory shared by every chat tab of this workbench session: the
     * agent writes lessons through the remember tool and reads them back in
     * later sessions from a file in the plugin's state area.
     */
    private AgentMemory ourLessonMemory;
    private SemanticIndex ourSemantic;
    private volatile boolean ourOverviewStale;

    /**
     * Project-local skills of the workspace, loaded once per view session
     * from {@code .colleague/skills} of the first open project; every chat
     * tab advertises the same catalog through load_skill.
     */
    private SkillRegistry ourSkillCatalog;

    /**
     * User slash commands of the workspace, loaded once per view session
     * from {@code .colleague/commands} of the first open project; every chat
     * tab expands matching input lines through the same catalog.
     */
    private SlashCommands ourCommandsCatalog;

    /**
     * Agent profiles of the workspace, loaded once per view session from
     * {@code .colleague/agents} of the first open project; the profile chip of
     * every chat tab lists the same set.
     */
    private List<AgentProfile> ourProfileCatalog;

    /** Debug-only HTTP transport for the chat page, open only by start-up flag. */
    private com.mad.edt.colleague.ui.internal.WebBridge ourWebBridge;

    /** Tracks the active text editor for the autocontext. */
    private final ActiveEditorWatcher ourActiveEditorWatcher = new ActiveEditorWatcher();

    private String ourLastAnswer = "";
    private ChatPanel ourLastAnsweredPanel;
    private boolean ourSelfTestDone;
    private boolean ourSelfTestPending;
    private boolean ourBrowserTestDone;
    private boolean ourBrowserTestPending;
    private boolean ourProbeDone;

    /**
     * Stages a question for the open instance or for the next view creation.
     *
     * @param theQuestion question text
     */
    public static void stageQuestion(final String theQuestion) {
        ourPendingQuestion = theQuestion;
    }

    /**
     * Stages a resource/project description attached to the next question.
     *
     * @param theContext context text
     */
    public static void stageContext(final String theContext) {
        ourPendingContext = theContext;
    }

    /**
     * @return the currently visible instance or {@code null}
     */
    public static ColleagueView getInstance() {
        return ourInstance;
    }

    @Override
    public void init(final org.eclipse.ui.IViewSite theSite) throws PartInitException {
        ourInstance = this;
        super.init(theSite);
    }

    @Override
    public void createPartControl(final Composite theParent) {
        ourRoot = new Composite(theParent, SWT.NONE);
        final GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        ourRoot.setLayout(rootLayout);

        // a stack of pages - agents, provider settings and chat - with
        // exactly one visible at any moment; the back arrow lives inside the
        // chat page header itself
        ourMobileStack = new Composite(ourRoot, SWT.NONE);
        ourMobileStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ourMobileStackLayout = new StackLayout();
        ourMobileStack.setLayout(ourMobileStackLayout);

        // the agents page is the way into a chat now: it lists the agents the
        // user talks to, while the provider list moved behind the gear and
        // became a pure settings page
        ourAgentsArea = new Composite(ourMobileStack, SWT.NONE);
        ourAgentsArea.setLayout(new FillLayout());
        ourAgents = new AgentsPanel(ourAgentsArea, this::profileCatalog,
            this::openAgent, () -> showMobilePage(ourContactsArea, "gear", "settings"),
            this::agentsLevelDirectory, this::refreshAgentCatalog);
        // a debugging door, not a feature: the socket only exists when the
        // start-up flag named a port for it
        openWebBridgeIfAsked();
        autoOpenChatIfAsked();

        ourContactsArea = new Composite(ourMobileStack, SWT.NONE);
        ourContactsArea.setLayout(new FillLayout());
        ourContacts = new ContactsPanel(ourContactsArea, this::openContact,
            this::onContactsLoaded, this::onProvidersChanged,
            () -> showMobilePage(ourAgentsArea, "back from providers", ""));

        ourTabsArea = new Composite(ourMobileStack, SWT.NONE);
        ourTabsArea.setLayout(new FillLayout());
        ourTabFolder = new CTabFolder(ourTabsArea, SWT.TOP | SWT.FLAT);
        ourTabFolder.setTabHeight(24);

        // without a browser backend the agents page cannot render, and the
        // provider tree stays the only working way into a chat there
        ourMobileStackLayout.topControl =
            ourAgents.hasBrowser() ? ourAgentsArea : ourContactsArea;

        ourCodeSearch = new CodeSearch();
        ourOverview = new ConfigurationOverview();
        // semantic layer defaults stay off for ordinary users; system
        // properties seed it for live checks without a settings UI
        SemanticSettings.loadOnce();
        if(!SemanticSettings.isConfigured()) {
            final String store = System.getProperty("colleague.semantic.store", "");
            final String model = System.getProperty("colleague.semantic.model", "");
            if(!store.isEmpty() && !model.isEmpty()) {
                SemanticSettings.configure(store,
                    System.getProperty("colleague.semantic.embed", ""), model);
            }
        }
        ourSemantic = SemanticIndex.tryCreate();
        if(ourSemantic != null) {
            System.err.println("[colleague] semantic layer on: store "
                + SemanticSettings.getStoreUrl() + ", model "
                + SemanticSettings.getModel());
        }
        // the whole-project index waits out its grace period as a system job:
        // reindexing competes with the user's builds, so it must not crowd
        // out the first minutes of the session (see StartupDeferral)
        StartupDeferral.scheduleIndexJob((monitor) -> {
            final int files = ourCodeSearch.reindex(monitor);
            System.err.println("[colleague] context index ready: " + files + " files");
            if(ourSemantic != null) {
                refreshSemanticUntilQuiet(monitor);
            }
            ourOverview.rebuild();
            System.err.println("[colleague] configuration overview ready: "
                + ourOverview.get().length() + " chars");
            if(Boolean.getBoolean("colleague.search.selftest")) {
                final long startedAt = System.nanoTime();
                final String found = ourCodeSearch.search("модуль объекта справочник проводка");
                final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                System.err.println("[colleague] search selftest: " + found.length()
                    + " chars in " + elapsedMs + " ms, first line: "
                    + found.lines().findFirst().orElse("<none>"));
                if(ourSemantic != null) {
                    final ContextSource hybrid =
                        ourSemantic.withKeyword(new KeywordContextSource(ourCodeSearch));
                    final long hybridStartedAt = System.nanoTime();
                    final String merged = hybrid.gather("модуль объекта справочник проводка");
                    System.err.println("[colleague] semantic selftest: " + merged.length()
                        + " chars in " + (System.nanoTime() - hybridStartedAt) / 1_000_000L
                        + " ms, " + ourSemantic.getStatus());
                }
            }
            return Status.OK_STATUS;
        });

        com.mad.edt.colleague.llm.ProviderStore.loadIntoSettings();
        // first run in a workspace that has no agent files at all: the user
        // gets a real first agent file to read, keep or delete right away
        if(profileCatalog().isEmpty()) {
            seedDefaultAgent();
        }
        ourContacts.reload();
        scheduleAgentProbe();

        // track the active editor for the autocontext
        ourActiveEditorWatcher.attach(getViewSite().getPage());
    }

    /**
     * Unattended live probe: a property-given delay after the view opens, one
     * real agent turn runs through the ordinary chat pipeline - edit mode on,
     * tools limited to the read-only set so no confirmation dialog can ever
     * block an unattended run, question fixed so its expected shape is known.
     * The turn traces its rounds to the console, holds the generator gate end
     * to end, and a concurrent background embedding pass either waits for it
     * or yields with its own logged abort. The probe exists so an unattended
     * check can decide pass/fail from the log alone.
     */
    private void scheduleAgentProbe() {
        final long delayMs = Long.getLong("colleague.agent.probe", 0L).longValue();
        if(delayMs <= 0L) {
            return;
        }
        System.err.println("[colleague] agent probe scheduled in " + delayMs + " ms");
        Display.getDefault().timerExec((int)delayMs, () -> {
            if(ourProbeDone || ourTabFolder.isDisposed()) {
                return;
            }
            final ChatPanel panel = targetPanelForStaged();
            if(panel == null) {
                System.err.println("[colleague] agent probe skipped: no chat panel yet");
                return;
            }
            ourProbeDone = true;
            AgentMode.setOn(true);
            panel.setToolSupplier(() -> WorkspaceTools.readOnly(ourCodeSearch, ourOverview,
                ourMetadataTools));
            System.err.println("[colleague] agent probe: edit mode on, read-only tools,"
                + " sending the probe question");
            panel.fillQuestion("Сколько всего объектов метаданных в конфигурации этого"
                + " проекта и сколько из них справочников? Сначала обязательно вызови"
                + " инструмент describe_configuration, затем ответь двумя числами."
                + " Ничего не изменяй.");
            panel.send();
        });
    }

    /**
     * Runs the semantic refresh pass and repeats it while trouble remains:
     * files that failed their own update stay untracked, so a retry pass
     * re-embeds exactly those and nothing else. Every pass waits out any
     * busy chat turn, so indexing never competes with an answer over the
     * model runtime, and a short pause separates the passes. After the
     * first pass fails to quiet down the endpoints are taken as genuinely
     * unavailable and the loop stops; the layer keeps serving what it has.
     *
     * @param theMonitor monitor of the owning index job
     */
    private void refreshSemanticUntilQuiet(final IProgressMonitor theMonitor) {
        for(int pass = 1; pass <= SEMANTIC_MAX_PASSES && !theMonitor.isCanceled(); pass++) {
            awaitQuietChat(theMonitor);
            final String outcome = ourSemantic.refreshAndLog(cappedSourceFiles());
            System.err.println("[colleague] semantic " + outcome
                + " | " + ourSemantic.getStatus());
            if(!passHasTrouble(outcome) || theMonitor.isCanceled()) {
                return;
            }
            if(pass < SEMANTIC_MAX_PASSES) {
                sleepQuietly(SEMANTIC_RETRY_DELAY_MS, theMonitor);
            }
        }
    }

    /**
     * @param theOutcome one-line pass description of {@link SemanticIndex#refreshAndLog}
     * @return whether the pass lost files of its own or never completed
     */
    private static boolean passHasTrouble(final String theOutcome) {
        if(theOutcome.startsWith("refresh failed")) {
            return true;
        }
        final String marker = ", failed ";
        final int mark = theOutcome.lastIndexOf(marker);
        return mark >= 0 && !"0".equals(theOutcome.substring(mark + marker.length()).trim());
    }

    /**
     * Holds a refresh pass back while any open panel is answering a question.
     *
     * @param theMonitor job monitor, the wait ends with a cancel too
     */
    private static void awaitQuietChat(final IProgressMonitor theMonitor) {
        while(ChatPanel.anyBusy() && !theMonitor.isCanceled()) {
            sleepQuietly(SEMANTIC_CHAT_WAIT_MS, theMonitor);
        }
    }

    private static void sleepQuietly(final long theMillis, final IProgressMonitor theMonitor) {
        try {
            Thread.sleep(theMillis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            theMonitor.setCanceled(true);
        }
    }

    /**
     * Brings one page of the shared stack to the front and traces the switch.
     *
     * @param thePage page to show
     * @param theCause call site requesting the switch, short and stable
     * @param theDetail extra context for the log, blank when there is none
     */
    private void showMobilePage(final Composite thePage, final String theCause,
        final String theDetail) {
        if(!thePage.isDisposed()) {
            ourMobileStackLayout.topControl = thePage;
            ourMobileStack.layout(true);
            ourRoot.layout(true);
            final int tabs = ourTabFolder == null || ourTabFolder.isDisposed()
                ? -1 : ourTabFolder.getItemCount();
            System.err.println("[colleague] mobile page -> " + PageSwitchNote.describe(
                thePage == ourTabsArea ? "chat tabs"
                    : thePage == ourAgentsArea ? "agents" : "contacts",
                theCause, tabs, theDetail)
                + " stack=" + ourMobileStack.getSize() + " page=" + thePage.getSize());
        }
    }

    @Override
    public void setFocus() {
        if(ourTabFolder.getSelection() != null) {
            final ChatPanel panel = (ChatPanel)ourTabFolder.getSelection().getData();
            if(panel != null) {
                panel.setFocus();
                return;
            }
        }
        if(ourMobileStackLayout.topControl == ourAgentsArea && ourAgents != null) {
            ourAgents.getControl().setFocus();
            return;
        }
        if(ourContacts != null) {
            ourContacts.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        if(ourInstance == this) {
            ourInstance = null;
        }
        if(ourWebBridge != null) {
            // the port belongs to the view: closing the view closes the door
            ourWebBridge.close();
            ourWebBridge = null;
        }
        // the ActiveEditorWatcher clears its snapshot on partClosed, nothing more needed
        if(ourAgents != null) {
            ourAgents.dispose();
        }
        if(ourContacts != null) {
            ourContacts.dispose();
        }
        if(ourTabFolder != null && !ourTabFolder.isDisposed()) {
            ourTabFolder.dispose();
        }
        super.dispose();
    }

    /**
     * @return the answer of the last successful question in any tab, never
     *         {@code null}
     */
    public String getLastAnswer() {
        return ourLastAnswer;
    }

    /**
     * Applies a question and context staged after this instance was created:
     * they go into the active (or default) chat tab without sending them.
     */
    public void applyStaged() {
        final ChatPanel panel = targetPanelForStaged();
        if(panel == null) {
            return;
        }
        final String staged = ourPendingQuestion;
        ourPendingQuestion = null;
        if(staged != null && !staged.isEmpty()) {
            panel.fillQuestion(staged);
        }
        final String stagedContext = ourPendingContext;
        ourPendingContext = null;
        if(stagedContext != null && !stagedContext.isEmpty()) {
            panel.attachContext(stagedContext);
        }
    }

    private ChatPanel targetPanelForStaged() {
        if(ourTabFolder.isDisposed()) {
            return null;
        }
        final CTabItem active = ourTabFolder.getSelection();
        if(active != null && active.getData() instanceof ChatPanel) {
            return (ChatPanel)active.getData();
        }
        final ContactsPanel.Contact preferred = ourDefaultContactCache;
        if(preferred != null) {
            return openContactNow(preferred);
        }
        return null;
    }

    private ContactsPanel.Contact ourDefaultContactCache;

    /** Startup budget seam: heavy builds move off the first-paint path. */
    private final StartupDeferral ourStartup = StartupDeferral.forDisplay();

    private void onContactsLoaded(final List<ContactsPanel.Contact> theAvailable) {
        if(ourTabFolder.isDisposed()) {
            return;
        }
        if(theAvailable.isEmpty()) {
            showNoContactsNotice();
            return;
        }
        ContactsPanel.Contact preferred = null;
        final String defaultId = LLMSettings.getDefaultProviderId();
        // the default contact is the default provider's own default model:
        // reading the model off the built-in local provider instead made the
        // preference follow a provider the user may not even have as default,
        // so the first model of the right provider won by listing order
        final com.mad.edt.colleague.llm.ProviderConfig defaultConfig =
            LLMSettings.getConfig(defaultId);
        final String configured = defaultConfig == null ? null : defaultConfig.getDefaultModel();
        for(final ContactsPanel.Contact contact : theAvailable) {
            if(!contact.getProviderId().equals(defaultId)) {
                continue;
            }
            if(preferred == null) {
                preferred = contact;
            }
            if(contact.getModel().equals(configured)) {
                preferred = contact;
                break;
            }
        }
        if(preferred == null) {
            preferred = theAvailable.get(0);
        }
        ourDefaultContactCache = preferred;
        // no auto-open on cold start any more: the agents page is where the
        // view lands, and a chat begins only when the user picks an agent
        maybeRunSelfTest();
    }

    private void showNoContactsNotice() {
        if(ourTabFolder.getItemCount() > 0) {
            return;
        }
        final CTabItem notice = new CTabItem(ourTabFolder, SWT.NONE);
        notice.setText("Нет доступных моделей");
        final Composite body = new Composite(ourTabFolder, SWT.NONE);
        body.setLayout(new FillLayout());
        final org.eclipse.swt.widgets.Label hint = new org.eclipse.swt.widgets.Label(body, SWT.WRAP);
        final StringBuilder where = new StringBuilder();
        for(final String id : LLMSettings.getProviderIds()) {
            final com.mad.edt.colleague.llm.ProviderConfig config = LLMSettings.getConfig(id);
            if(config != null) {
                where.append('\n').append(id).append(": ").append(config.getBaseUrl());
            }
        }
        hint.setText(
            "Ни один LLM-провайдер недоступен." + where
                + "\nПроверьте их запуск и нажмите «Обновить» в панели контактов.");
        notice.setControl(body);
        ourTabFolder.setSelection(notice);
    }

    /**
     * Stack fallback for an emptied tab folder: with no chat tabs left the
     * tabs page shows nothing but blank space, so the visible page becomes
     * the agents list the user can pick a new chat from; without a browser
     * backend the provider tree stays the working entry instead. Runs on the
     * UI queue after a tab item was disposed.
     */
    private void fallBackToContactsIfEmpty() {
        if(ourTabFolder.isDisposed() || ourTabFolder.getItemCount() > 0) {
            return;
        }
        showMobilePage(ourAgents != null && ourAgents.hasBrowser()
            ? ourAgentsArea : ourContactsArea, "tabs empty", "");
    }

    /**
     * @return the shared lesson memory, opened once per workbench session
     *         over a file in the plugin's own state area; when the state
     *         area is unavailable the temporary directory keeps the lesson
     *         file working in a degraded way instead of failing the view
     */
    private AgentMemory lessonMemory() {
        if(ourLessonMemory == null) {
            final ColleagueUIPlugin plugin = ColleagueUIPlugin.getDefault();
            final Path state = plugin != null
                ? plugin.getStateLocation().toFile().toPath()
                : Paths.get(System.getProperty("java.io.tmpdir"));
            ourLessonMemory = new AgentMemory(state.resolve("agent-lessons.txt"));
        }
        return ourLessonMemory;
    }

    /**
     * Project-local skills advertised to every agent turn: loaded once per
     * view session from {@code .colleague/skills} under the first open
     * workspace project. A missing or unreadable directory leaves the catalog
     * empty, which simply runs the chats without the skill layer instead of
     * failing the view.
     *
     * @return shared skill catalog, never {@code null}
     */
    private SkillRegistry skillCatalog() {
        if(ourSkillCatalog == null) {
            final SkillRegistry registry = new SkillRegistry();
            final java.io.File skillsDir = colleagueDirectory("skills");
            if(skillsDir != null) {
                try {
                    for(final SkillDefinition skill : new SkillLoader().loadAll(skillsDir)) {
                        registry.register(skill);
                    }
                } catch (final java.io.IOException ex) {
                    System.err.println("[colleague] skills directory unreadable: "
                        + ex.getMessage());
                }
            }
            ourSkillCatalog = registry;
        }
        return ourSkillCatalog;
    }

    /**
     * User slash commands advertised in the chat line: loaded once per view
     * session from {@code .colleague/commands} under the first open workspace
     * project. A missing or unreadable directory leaves the catalog empty, so
     * slash lines stay ordinary chat text instead of failing the view.
     *
     * @return shared command catalog, never {@code null}
     */
    private SlashCommands commandsCatalog() {
        if(ourCommandsCatalog == null) {
            final java.io.File commandsDir = colleagueDirectory("commands");
            final List<CommandDefinition> commands = new ArrayList<>();
            if(commandsDir != null) {
                try {
                    commands.addAll(new CommandLoader().loadAll(commandsDir));
                } catch (final java.io.IOException ex) {
                    System.err.println("[colleague] commands directory unreadable: "
                        + ex.getMessage());
                }
            }
            ourCommandsCatalog = new SlashCommands(commands);
        }
        return ourCommandsCatalog;
    }

    /**
     * Agent profiles the pages offer: merged from the two catalog levels -
     * the personal {@code .colleague/agents} under the user home and the
     * project one under the first open workspace project - loaded once per
     * view session. A missing or unreadable level leaves its part empty, so
     * the chats simply run without those profiles instead of failing the view.
     *
     * @return shared profile list, never {@code null}
     */
    private List<AgentProfile> profileCatalog() {
        if(ourProfileCatalog == null) {
            final List<AgentProfile> merged;
            try {
                merged = AgentCatalog.load(personalAgentsDirectory(),
                    colleagueDirectory("agents"));
            } catch (final java.io.IOException ex) {
                System.err.println("[colleague] agents directories unreadable: "
                    + ex.getMessage());
                ourProfileCatalog = List.of();
                return ourProfileCatalog;
            }
            ourProfileCatalog = merged;
        }
        return ourProfileCatalog;
    }

    /**
     * The user-wide agents directory {@code .colleague/agents} below the home
     * directory: profiles shared by every workspace, kept out of project
     * repositories so the user can version them separately.
     *
     * @return the personal directory whether or not it exists; a missing one
     *         simply contributes no profiles to the merge
     */
    private static java.io.File personalAgentsDirectory() {
        return Paths.get(System.getProperty("user.home"), ".colleague", "agents")
            .toFile();
    }

    /**
     * Drops the cached profile catalog so the next read sees the profile files
     * as they are now, and re-pushes the agents page from the fresh catalog.
     */
    void refreshAgentCatalog() {
        ourProfileCatalog = null;
        if(ourAgents != null) {
            ourAgents.refresh();
        }
    }

    /**
     * Maps a page level name to the directory its agent files live in.
     *
     * @param theLevel {@code personal} for the user-wide directory,
     *            {@code project} for the first open project
     * @return the directory to read from or write into (the writer creates it
     *            when missing), {@code null} when the level has no target
     *            right now - no open project for the project level
     */
    private java.io.File agentsLevelDirectory(final String theLevel) {
        if("personal".equals(theLevel)) {
            return personalAgentsDirectory();
        }
        if("project".equals(theLevel)) {
            return colleagueDirectoryForWriting("agents");
        }
        return null;
    }

    /**
     * The {@code .colleague/<theName>} directory path of the first open
     * project, used where a directory must still be created; unlike
     * {@link #colleagueDirectory(String)} it answers even when the folder
     * does not exist yet.
     *
     * @param theName folder name below {@code .colleague}
     * @return the directory path whether or not it exists, {@code null} when
     *         no project is open
     */
    private java.io.File colleagueDirectoryForWriting(final String theName) {
        for(final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if(!project.isOpen()) {
                continue;
            }
            return project.getLocation().append(".colleague/" + theName).toFile();
        }
        return null;
    }

    /**
     * Writes the seed agent into the first open project while the catalog is
     * still empty. The seed ships as an ordinary profile file inside this
     * bundle ({@code seed/agents/}), so its text is reviewable in the repository
     * and the user finds exactly that file to read, correct or delete; it is
     * written only into an empty catalog, so a deleted seed stays deleted on the
     * next start.
     */
    private void seedDefaultAgent() {
        final java.io.File dir = colleagueDirectoryForWriting("agents");
        if(dir == null) {
            return;
        }
        final String text = readBundleText(SEED_AGENT_RESOURCE);
        if(text.isEmpty()) {
            return;
        }
        try {
            AgentProfileWriter.saveText(dir, SEED_AGENT_ID, text);
            refreshAgentCatalog();
        } catch (final java.io.IOException ex) {
            System.err.println("[colleague] seed agent not written: " + ex.getMessage());
        }
    }

    /**
     * Reads one text resource of this plugin's bundle as UTF-8.
     *
     * @param thePath bundle-relative path, e.g. {@code seed/agents/x.md}
     * @return the text, empty when the resource is missing or unreadable - a
     *         shipped default never breaks the view
     */
    private static String readBundleText(final String thePath) {
        final Bundle bundle = FrameworkUtil.getBundle(ColleagueView.class);
        if(bundle == null) {
            return "";
        }
        final java.net.URL entry = bundle.getEntry(thePath);
        if(entry == null) {
            System.err.println("[colleague] missing bundle resource: " + thePath);
            return "";
        }
        try (java.io.InputStream stream = entry.openStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException ex) {
            System.err.println("[colleague] resource unreadable: " + thePath + ": "
                + ex.getMessage());
            return "";
        }
    }

    /**
     * Finds the workspace-local folder {@code .colleague/<theName>} under the
     * first open project of the workspace; the same place skills, commands and
     * other project extensions of the plugin live.
     *
     * @param theName folder name below {@code .colleague}
     * @return existing directory, {@code null} when no open project has one
     */
    private java.io.File colleagueDirectory(final String theName) {
        for(final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if(!project.isOpen()) {
                continue;
            }
            final java.io.File dir = project.getLocation()
                .append(".colleague/" + theName).toFile();
            if(dir.isDirectory()) {
                return dir;
            }
            break;
        }
        return null;
    }

    /**
     * Opens a contact the way the ordinary UI paths do (agent card click,
     * selftests): the tab itself appears right away, but the heavy chat page
     * - the embedded browser and its controls - is queued behind the first
     * paint of the tab, so switching to the chat never waits on the browser
     * widget initialization.
     *
     * @param theContact provider, model and agent triple to open
     */
    private void openContact(final ContactsPanel.Contact theContact) {
        if(ourTabFolder.isDisposed()) {
            return;
        }
        if(existingTabFor(theContact) != null) {
            return;
        }
        final CTabItem tab = createTabFor(theContact);
        final Composite body = createTabBody(tab);
        final ChatSession session = sessionFor(theContact);
        ourStartup.defer(() -> {
            // the tab may have been closed while its build waited for the
            // paint tick: building into a disposed shell would only throw
            if(!tab.isDisposed() && !body.isDisposed() && !ourTabFolder.isDisposed()) {
                buildChatPanel(tab, body, session, theContact);
            }
        });
    }

    /**
     * Opens the chat tab of one agent card: the built-in colleague runs on the
     * default provider and model as before, a workspace profile keeps its own
     * identity in the tab (name in the title, own conversation) while its
     * model is honored when the default provider actually serves it. With no
     * reachable provider there is nothing to run the agent on, so the gear's
     * settings page opens instead of a chat that could only fail.
     *
     * @param theName profile name from the card, empty for the built-in
     *            default colleague
     */
    private void openAgent(final String theName) {
        if(ourTabFolder.isDisposed()) {
            return;
        }
        final ContactsPanel.Contact preferred = ourDefaultContactCache;
        if(preferred == null) {
            showMobilePage(ourContactsArea, "no providers yet", theName);
            return;
        }
        final AgentProfile profile = theName == null || theName.isEmpty()
            ? null : findProfile(theName);
        final String model = agentModelFor(profile, preferred);
        openContact(new ContactsPanel.Contact(preferred.getProviderId(), model,
            profile == null ? "" : profile.getName()));
    }

    /**
     * @param theName profile name as the card carries it
     * @return the catalog profile of that name, {@code null} when the catalog
     *         no longer has it (a removed file between two page refreshes)
     */
    private AgentProfile findProfile(final String theName) {
        for(final AgentProfile profile : profileCatalog()) {
            if(profile.getName().equals(theName)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Resolves which model an agent tab asks: the profile's own model when it
     * names one, otherwise the default contact's model. A profile asking for
     * a model the default provider does not list still runs against it - the
     * provider answers with its own clear error, which is more honest than
     * silently running the agent on a model the user never picked.
     *
     * @param theProfile picked profile, {@code null} for the built-in one
     * @param thePreferred default provider and model pair
     * @return model name for the agent's chat tab
     */
    private static String agentModelFor(final AgentProfile theProfile,
        final ContactsPanel.Contact thePreferred) {
        if(theProfile != null && theProfile.getModelName() != null
            && !theProfile.getModelName().isEmpty()) {
            return theProfile.getModelName();
        }
        return thePreferred.getModel();
    }

    /**
     * Opens a contact and builds the chat page inline. Only for the paths
     * that keep using the returned panel right away: staged questions and
     * the selftests.
     *
     * @param theContact provider and model pair to open
     * @return the chat panel of the contact's tab, {@code null} when the
     *         tab exists but its build is still queued from an earlier
     *         deferred open
     */
    private ChatPanel openContactNow(final ContactsPanel.Contact theContact) {
        if(ourTabFolder.isDisposed()) {
            return null;
        }
        final CTabItem existing = existingTabFor(theContact);
        if(existing != null) {
            return existing.getData() instanceof ChatPanel ? (ChatPanel)existing.getData() : null;
        }
        final CTabItem tab = createTabFor(theContact);
        final Composite body = createTabBody(tab);
        return buildChatPanel(tab, body, sessionFor(theContact), theContact);
    }

    /**
     * Selects the tab of this contact when the folder already has one. The
     * text check alone is enough: a tab is created only by these open paths,
     * one per display name, and its panel build never runs twice for one tab.
     *
     * @param theContact provider, model and agent triple
     * @return the existing tab, {@code null} when there is none
     */
    private CTabItem existingTabFor(final ContactsPanel.Contact theContact) {
        for(final CTabItem item : ourTabFolder.getItems()) {
            if(theContact.getDisplayName().equals(item.getText())) {
                ourTabFolder.setSelection(item);
                showMobilePage(ourTabsArea, "existing tab", theContact.getDisplayName());
                return item;
            }
        }
        return null;
    }

    /**
     * Creates the closed-able tab shell for one contact.
     *
     * @param theContact provider, model and agent triple
     * @return the new tab item
     */
    private CTabItem createTabFor(final ContactsPanel.Contact theContact) {
        final CTabItem tab = new CTabItem(ourTabFolder, SWT.CLOSE);
        tab.setText(theContact.getDisplayName());
        // closing the last chat tab must not leave the empty tab page on
        // screen: the agents list is the way back into a chat, so the stack
        // falls back to it once the folder runs out of tabs. the close button
        // disposes the item, and the folder itself gives no usable "now empty"
        // event, so the item's own dispose is the trigger
        tab.addDisposeListener((final DisposeEvent theEvent) ->
            Display.getDefault().asyncExec(this::fallBackToContactsIfEmpty));
        return tab;
    }

    /**
     * Puts the empty page body under the tab and switches the stack to the
     * chat page.
     *
     * @param theTab tab to attach the body to
     * @return the body composite the chat panel will live in
     */
    private Composite createTabBody(final CTabItem theTab) {
        // switch the page over before the browser is created: at cold start the
        // preferred contact opens automatically, and a browser created in a
        // hidden zero-sized page paints nothing
        showMobilePage(ourTabsArea, "tab body", theTab.getText());
        final Composite body = new Composite(ourTabFolder, SWT.NONE);
        body.setLayout(new FillLayout());
        theTab.setControl(body);
        ourTabFolder.setSelection(theTab);
        return body;
    }

    /**
     * Session for one contact, shared by all tabs of the same provider and
     * model pair so a conversation survives closing and reopening a tab. A
     * session whose captured configuration no longer matches the saved one
     * (the user corrected the address or key) is replaced, not reused.
     *
     * @param theContact provider and model pair
     * @return the live session of this contact
     */
    private ChatSession sessionFor(final ContactsPanel.Contact theContact) {
        final ProviderConfig current = LLMSettings.getConfig(theContact.getProviderId());
        return ourSessions.sessionFor(theContact.getKey(), current, () -> new ChatSession(
            LLMProviderFactory.create(current != null ? current
                : LLMSettings.getConfig(LLMSettings.getDefaultProviderId())),
            SYSTEM_PROMPT));
    }

    /**
     * The saved provider list moved: a provider was corrected or removed.
     * Every tab whose session captured the previous endpoint is retired -
     * that conversation belonged to an address the user has left behind, and
     * keeping the tab open would replay the failure the correction was meant
     * to fix. The next open of the contact builds against the saved address.
     */
    private void onProvidersChanged() {
        if(ourTabFolder == null || ourTabFolder.isDisposed()) {
            return;
        }
        final List<CTabItem> stale = new ArrayList<>();
        for(final CTabItem item : ourTabFolder.getItems()) {
            final Object data = item.getData();
            if(data instanceof ChatPanel) {
                final ChatSession session = ((ChatPanel)data).getSession();
                if(!ourSessions.isCurrent(session,
                    LLMSettings.getConfig(session.getProvider().getId()))) {
                    stale.add(item);
                }
            }
        }
        for(final CTabItem item : stale) {
            item.dispose();
        }
    }

    /**
     * The heavy half of opening a contact: builds the chat panel with its
     * embedded browser and wires the view into it. Kept out of the tab
     * shell creation so the deferred open can run it after the first paint.
     *
     * @param theTab tab shell to fill
     * @param theBody body composite for the panel
     * @param theSession conversation session of this contact
     * @param theContact provider and model pair
     * @return the built chat panel
     */
    /**
     * Debug seam for an unattended verification run: opens one chat tab under
     * the built-in agent without a click, which is what gives the web bridge a
     * panel to attach to. Off unless {@code -Dcolleague.debug.autochat=true}.
     */
    private void autoOpenChatIfAsked() {
        if(!Boolean.getBoolean("colleague.debug.autochat")) {
            return;
        }
        scheduleAutoChat(0);
    }

    /**
     * Waits for the provider probe to name a provider and model pair, then
     * opens the debug chat tab. The pair only exists once the startup probe has
     * talked to an endpoint, so opening right away would land on the settings
     * page instead of a chat; the seam says what it is waiting for rather than
     * reporting a chat that never appeared.
     *
     * @param theAttempt attempts already made, capped so a dead endpoint cannot
     *            keep the display timer alive forever
     */
    private void scheduleAutoChat(final int theAttempt) {
        ourRoot.getDisplay().timerExec(theAttempt == 0 ? 3_000 : 2_000, () -> {
            if(ourRoot == null || ourRoot.isDisposed()) {
                return;
            }
            if(ourDefaultContactCache == null) {
                if(theAttempt >= 20) {
                    System.err.println("[colleague] auto chat gave up: no provider answered"
                        + " the probe, nothing to open");
                    return;
                }
                System.err.println("[colleague] auto chat waiting for a provider probe ("
                    + (theAttempt + 1) + ")");
                scheduleAutoChat(theAttempt + 1);
                return;
            }
            openAgent("");
            System.err.println("[colleague] auto chat opened (debug), provider "
                + ourDefaultContactCache.getProviderId());
        });
    }

    /**
     * Opens the debug web bridge when the start-up flag asked for a port. The
     * bridge is a debugging door for an agent or a browser test: it serves the
     * same chat page over the loopback interface and guards every data request
     * with a generated token. Without the flag nothing listens at all.
     */
    private void openWebBridgeIfAsked() {
        final int port = com.mad.edt.colleague.ui.internal.WebBridge.requestedPort();
        if(port == 0) {
            return;
        }
        try {
            ourWebBridge = com.mad.edt.colleague.ui.internal.WebBridge.start(port);
            System.out.println("[colleague] web bridge (debug): http://"
                + ourWebBridge.host() + ":" + ourWebBridge.port() + "/");
        } catch (final java.io.IOException ex) {
            System.err.println("[colleague] web bridge not started: " + ex.getMessage());
        }
    }

    /**
     * Lets the debug bridge watch and drive one chat tab: its pushes mirror the
     * panel's events, its page calls enter the panel's own routing table. The
     * bridge answers whichever tab attached last, which is what a person
     * watching a browser tab expects.
     *
     * @param thePanel chat tab just built
     */
    private void attachWebBridge(final ChatPanel thePanel) {
        if(ourWebBridge == null) {
            return;
        }
        ourWebBridge.setPageCallSink(thePanel::pageCall);
        thePanel.attachMirrorTransport(ourWebBridge);
    }

    /**
     * Collects the metadata trees the agent tools may read: the {@code src}
     * folder of every open EDT project, named by the project so answers carry
     * a workspace-relative path. The list is rebuilt on every tool call, so a
     * configuration opened after the chat started is searchable at once and a
     * closed one disappears instead of failing the search.
     *
     * @return metadata roots of the open projects, empty when none is open
     */
    private List<MetadataTools.Root> metadataRoots() {
        final List<MetadataTools.Root> roots = new ArrayList<>();
        for(final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if(!project.isOpen() || project.getLocation() == null) {
                continue;
            }
            final Path src = project.getLocation().toFile().toPath().resolve("src");
            if(Files.isDirectory(src)) {
                roots.add(new MetadataTools.Root(project.getName(), src));
            }
        }
        return roots;
    }

    private ChatPanel buildChatPanel(final CTabItem theTab, final Composite theBody,
        final ChatSession theSession, final ContactsPanel.Contact theContact) {
        final ChatPanel panel = new ChatPanel(theBody, theSession, theContact.getModel());
        panel.attachThemeAnchor(ourRoot);
        panel.setToolSupplier(() -> AgentMode.isOn()
            ? WorkspaceTools.withWrite(ourCodeSearch, ourOverview, ourMetadataTools)
            : WorkspaceTools.readOnly(ourCodeSearch, ourOverview, ourMetadataTools));
        panel.setLessonMemory(lessonMemory());
        panel.setMcpCatalog(ourMcpCatalog);
        panel.setSkillSupplier(this::skillCatalog);
        panel.setCommandSupplier(this::commandsCatalog);
        panel.setProfileSupplier(this::profileCatalog);
        panel.setAutoContextSupplier(this::collectAutoContext);
        // the agents page is the way back out of a chat; the gear opens the
        // provider settings page instead. without a browser backend the
        // agents page cannot render, so back keeps the provider tree there
        final boolean agentsLive = ourAgents != null && ourAgents.hasBrowser();
        panel.setOnBack(() -> showMobilePage(agentsLive ? ourAgentsArea : ourContactsArea,
            "back arrow", theContact.getDisplayName()));
        panel.setOnOpenSettings(() -> showMobilePage(ourContactsArea,
            "gear", theContact.getDisplayName()));
        // an agent tab speaks as its agent from the first turn, not only
        // after the chip was touched
        panel.setInitialAgent(theContact.getAgentName());
        attachWebBridge(panel);
        theTab.setData(panel);
        panel.setAnswerListener(answer -> {
            ourLastAnswer = answer;
            ourLastAnsweredPanel = panel;
            if(ourSelfTestPending) {
                ourSelfTestPending = false;
                System.err.println("[colleague] UI selftest answer: ["
                    + answer.replace('\n', ' ') + "]");
            }
            if(ourBrowserTestPending) {
                ourBrowserTestPending = false;
                System.err.println("[colleague] browser selftest answer: ["
                    + answer.replace('\n', ' ').substring(0,
                        Math.min(120, answer.replace('\n', ' ').length())) + "]");
            }
        });
        return panel;
    }

    /**
     * Automatic context for a question when none was attached by hand: code
     * search snippets for the question text, plus the workspace configuration
     * picture so the model always knows which configuration it is talking
     * about, not only which lines of code mention the question words. When the
     * picture is missing (its first build raced the question) a rebuild is
     * scheduled for the following question. Runs on a background job thread.
     */
    private String collectAutoContext(final String theQuestion) {
        final String snippets = collectCodeSnippets(theQuestion);
        String overview = ourOverview == null ? "" : ourOverview.get();
        if(overview.isEmpty() && ourOverview != null && !ourOverviewStale) {
            ourOverviewStale = true;
            Job.create("Colleague: configuration picture", (monitor) -> {
                ourOverview.rebuild();
                ourOverviewStale = false;
                return Status.OK_STATUS;
            }).schedule();
        }
        final String editor = ourActiveEditorWatcher.render();
        if(overview.isEmpty() && editor.isEmpty()) {
            return snippets;
        }
        final StringBuilder combined = new StringBuilder();
        if(!snippets.isEmpty()) {
            combined.append(snippets);
        }
        if(!overview.isEmpty()) {
            if(combined.length() > 0) {
                combined.append("\n\n");
            }
            combined.append("Open 1C configuration in this workspace:\n");
            combined.append(overview);
        }
        if(!editor.isEmpty()) {
            if(combined.length() > 0) {
                combined.append("\n\n");
            }
            combined.append(editor);
        }
        return ContextBudget.trimMiddle(combined.toString(), 3_000);
    }

    /**
     * Code snippets for one question: keyword search on its own, or the
     * hybrid keyword-plus-semantic merge once a vector store is configured.
     * The semantic section stays silent whenever the index is unprepared or
     * an endpoint fails, so a question never waits on the vector layer.
     *
     * @param theQuestion question text
     * @return snippet blocks for the model, empty when nothing matched
     */
    private String collectCodeSnippets(final String theQuestion) {
        if(ourCodeSearch == null) {
            return "";
        }
        final ContextSource keyword = new KeywordContextSource(ourCodeSearch);
        return ourSemantic == null
            ? keyword.gather(theQuestion)
            : ourSemantic.withKeyword(keyword).gather(theQuestion);
    }

    /**
     * Searchable files for one semantic refresh pass. A live check can cap
     * the pass with {@code -Dcolleague.semantic.max-files=N} so a huge
     * workspace indexes a representative subset instead of every file; file
     * fingerprints only live inside one session, so a capped pass never
     * purges what earlier sessions stored.
     *
     * @return file source for {@link SemanticIndex#refreshAndLog}
     */
    private SemanticIndex.FileSource cappedSourceFiles() {
        final int cap = Integer.getInteger("colleague.semantic.max-files", 0).intValue();
        if(cap <= 0) {
            return ourCodeSearch::forEachSourceFile;
        }
        return acceptor -> {
            final int[] accepted = new int[1];
            ourCodeSearch.forEachSourceFile((path, displayPath) -> {
                if(accepted[0] < cap) {
                    accepted[0]++;
                    acceptor.accept(path, displayPath);
                }
            });
        };
    }

    private void maybeRunSelfTest() {
        if(!Boolean.getBoolean("colleague.ui.selftest") || ourSelfTestDone) {
            return;
        }
        final ContactsPanel.Contact preferred = ourDefaultContactCache;
        if(preferred == null) {
            return;
        }
        ourSelfTestDone = true;
        ourSelfTestPending = true;
        final ChatPanel panel = openContactNow(preferred);
        if(panel == null) {
            return;
        }
        panel.attachContext("File: demo/Demo.bsl\nContent:\n&НаКлиенте\n"
            + "Процедура ДемонстрацияКонтекста() КонецПроцедуры");
        panel.fillQuestion("Какая процедура описана в приложенном контексте? Ответь одним словом.");
        System.err.println("[colleague] UI selftest: sending question with context");
        panel.send();
        scheduleBrowserTest(panel);
    }

    /**
     * Second selftest phase: after the chat page had time to connect to the
     * bridge, ask the very same panel through its normal question pipeline.
     * The question then round-trips: events leave for the page, the page
     * renders them, and the answer arrives as an ordinary LLM reply. The
     * markers show the full chain works while the page is actually attached.
     */
    private void scheduleBrowserTest(final ChatPanel thePanel) {
        if(!Boolean.getBoolean("colleague.browser.selftest") || ourBrowserTestDone) {
            return;
        }
        ourBrowserTestDone = true;
        Display.getDefault().timerExec(18000, () -> {
            if(ourTabFolder.isDisposed() || thePanel == null) {
                return;
            }
            final com.mad.edt.colleague.llm.ProviderConfig local =
                LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID);
            final String secondModel =
                local == null ? null : local.getDefaultModel();
            if(secondModel == null || secondModel.isEmpty()) {
                System.err.println("[colleague] browser selftest phase2: skipped, the "
                    + "provider has no saved default model to open a second tab with");
                return;
            }
            System.err.println("[colleague] browser selftest phase2: opening a second "
                + "model tab (the switch-model path that used to hang)");
            openContact(new ContactsPanel.Contact(
                LLMSettings.LOCAL_PROVIDER_ID, secondModel));
            System.err.println("[colleague] browser selftest phase2: second tab open, "
                + "UI thread alive");
        });
        Display.getDefault().timerExec(25000, () -> {
            if(ourTabFolder.isDisposed() || thePanel == null) {
                return;
            }
            ourBrowserTestPending = true;
            System.err.println("[colleague] browser selftest: sending while the page "
                + "is attached: bridgeReady=" + thePanel.isBridgeReady());
            thePanel.fillQuestion("Повтори одним словом: какая процедура была в контексте "
                + "выше?");
            thePanel.send();
        });
    }

    /**
     * Reports activation problems of this bundle to the platform log.
     *
     * @param theStatus status to log
     */
    static void log(final IStatus theStatus) {
        final Bundle bundle = FrameworkUtil.getBundle(ColleagueView.class);
        if(bundle != null) {
            org.eclipse.core.runtime.Platform.getLog(bundle).log(theStatus);
        }
    }
}
