package com.mad.edt.colleague.ui.view;

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
import com.mad.edt.colleague.skills.SkillDefinition;
import com.mad.edt.colleague.skills.SkillLoader;
import com.mad.edt.colleague.skills.SkillRegistry;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;
import com.mad.edt.colleague.ui.internal.ChatPanel;
import com.mad.edt.colleague.ui.internal.ChatSession;
import com.mad.edt.colleague.ui.internal.ContactsPanel;
import com.mad.edt.colleague.ui.internal.StartupDeferral;
import com.mad.edt.colleague.ui.internal.WorkspaceTools;

/**
 * Chat-messenger shell of the Colleague assistant: the contacts panel lists
 * configured LLM providers and their models, selecting a contact opens a chat
 * tab. Each contact keeps its own conversation through the session registry,
 * so closing and reopening a tab preserves history while the view lives.
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

    /** Question staged by handlers before the view instance exists. */
    private static volatile String ourPendingQuestion;

    /** Resource/project context staged together with the question. */
    private static volatile String ourPendingContext;

    /** The single visible instance (the view is not multiplicable). */
    private static volatile ColleagueView ourInstance;

    private final Map<String, ChatSession> ourSessions = new HashMap<>();
    private final Map<String, ChatPanel> ourPanels = new HashMap<>();

    private Composite ourRoot;
    private Composite ourContactsArea;
    private Composite ourTabsArea;
    private Composite ourMobileStack;
    private StackLayout ourMobileStackLayout;
    private ContactsPanel ourContacts;
    private CTabFolder ourTabFolder;
    private CodeSearch ourCodeSearch;
    private ConfigurationOverview ourOverview;

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

        // a two-page stack: contacts and chat, exactly one visible at any
        // moment; the back arrow lives inside the chat page header itself
        ourMobileStack = new Composite(ourRoot, SWT.NONE);
        ourMobileStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ourMobileStackLayout = new StackLayout();
        ourMobileStack.setLayout(ourMobileStackLayout);

        ourContactsArea = new Composite(ourMobileStack, SWT.NONE);
        ourContactsArea.setLayout(new FillLayout());
        ourContacts = new ContactsPanel(ourContactsArea, this::openContact, this::onContactsLoaded);

        ourTabsArea = new Composite(ourMobileStack, SWT.NONE);
        ourTabsArea.setLayout(new FillLayout());
        ourTabFolder = new CTabFolder(ourTabsArea, SWT.TOP | SWT.FLAT);
        ourTabFolder.setTabHeight(24);

        ourMobileStackLayout.topControl = ourContactsArea;

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
        ourContacts.reload();
        scheduleAgentProbe();
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
            panel.setToolSupplier(() -> WorkspaceTools.readOnly(ourCodeSearch, ourOverview));
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

    private void showMobilePage(final Composite thePage) {
        if(!thePage.isDisposed()) {
            ourMobileStackLayout.topControl = thePage;
            ourMobileStack.layout(true);
            ourRoot.layout(true);
            System.err.println("[colleague] mobile page -> "
                + (thePage == ourTabsArea ? "chat tabs" : "contacts")
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
        if(ourContacts != null) {
            ourContacts.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        if(ourInstance == this) {
            ourInstance = null;
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
        final com.mad.edt.colleague.llm.ProviderConfig defaultConfig =
            LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID);
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
        if(ourTabFolder.getSelection() == null) {
            openContact(preferred);
        }
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
        hint.setText(
            "Ни один LLM-провайдер недоступен.\nЛокальный рантайм: "
                + LLMSettings.LOCAL_RUNTIME_URL + "\nПроверьте его запуск и нажмите «Обновить» "
                + "в панели контактов.");
        notice.setControl(body);
        ourTabFolder.setSelection(notice);
    }

    /**
     * Stack fallback for an emptied tab folder: with no chat tabs left the
     * tabs page shows nothing but blank space, so the visible page becomes
     * the contacts list the user can pick a new chat from. Runs on the UI
     * queue after a tab item was disposed.
     */
    private void fallBackToContactsIfEmpty() {
        if(ourTabFolder.isDisposed() || ourTabFolder.getItemCount() > 0) {
            return;
        }
        showMobilePage(ourContactsArea);
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
     * Opens a contact the way the ordinary UI paths do (list click, cold
     * start auto-open): the tab itself appears right away, but the heavy
     * chat page - the embedded browser and its controls - is queued behind
     * the first paint of the tab, so switching to the chat never waits on
     * the browser widget initialization.
     *
     * @param theContact provider and model pair to open
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
     * Selects the tab of this model when the folder already has one. The
     * text check alone is enough: a tab is created only by these open paths,
     * one per model, and its panel build never runs twice for one tab.
     *
     * @param theContact provider and model pair
     * @return the existing tab, {@code null} when there is none
     */
    private CTabItem existingTabFor(final ContactsPanel.Contact theContact) {
        for(final CTabItem item : ourTabFolder.getItems()) {
            if(theContact.getModel().equals(item.getText())) {
                ourTabFolder.setSelection(item);
                showMobilePage(ourTabsArea);
                return item;
            }
        }
        return null;
    }

    /**
     * Creates the closed-able tab shell for one contact.
     *
     * @param theContact provider and model pair
     * @return the new tab item
     */
    private CTabItem createTabFor(final ContactsPanel.Contact theContact) {
        final CTabItem tab = new CTabItem(ourTabFolder, SWT.CLOSE);
        tab.setText(theContact.getModel());
        // closing the last chat tab must not leave the empty tab page on
        // screen: the provider list is the only way back into a chat, so the
        // stack falls back to it once the folder runs out of tabs. the close
        // button disposes the item, and the folder itself gives no usable
        // "now empty" event, so the item's own dispose is the trigger
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
        showMobilePage(ourTabsArea);
        final Composite body = new Composite(ourTabFolder, SWT.NONE);
        body.setLayout(new FillLayout());
        theTab.setControl(body);
        ourTabFolder.setSelection(theTab);
        return body;
    }

    /**
     * Session for one contact, shared by all tabs of the same provider and
     * model pair so a conversation survives closing and reopening a tab.
     *
     * @param theContact provider and model pair
     * @return the live session of this contact
     */
    private ChatSession sessionFor(final ContactsPanel.Contact theContact) {
        return ourSessions.computeIfAbsent(theContact.getKey(), key ->
            new ChatSession(
                LLMProviderFactory.create(LLMSettings.getConfig(theContact.getProviderId())),
                SYSTEM_PROMPT));
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
    private ChatPanel buildChatPanel(final CTabItem theTab, final Composite theBody,
        final ChatSession theSession, final ContactsPanel.Contact theContact) {
        final ChatPanel panel = new ChatPanel(theBody, theSession, theContact.getModel());
        panel.attachThemeAnchor(ourRoot);
        panel.setToolSupplier(() -> AgentMode.isOn()
            ? WorkspaceTools.withWrite(ourCodeSearch, ourOverview)
            : WorkspaceTools.readOnly(ourCodeSearch, ourOverview));
        panel.setLessonMemory(lessonMemory());
        panel.setSkillSupplier(this::skillCatalog);
        panel.setCommandSupplier(this::commandsCatalog);
        panel.setAutoContextSupplier(this::collectAutoContext);
        panel.setOnBack(() -> showMobilePage(ourContactsArea));
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
        if(overview.isEmpty()) {
            return snippets;
        }
        return snippets + "\n\nOpen 1C configuration in this workspace:\n" + overview;
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
            System.err.println("[colleague] browser selftest phase2: opening a second "
                + "model tab (the switch-model path that used to hang)");
            openContact(new ContactsPanel.Contact(
                LLMSettings.LOCAL_PROVIDER_ID, "llama3.1:8b"));
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
