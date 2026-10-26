package com.mad.edt.colleague.ui.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.context.CodeSearch;
import com.mad.edt.colleague.context.ConfigurationOverview;
import com.mad.edt.colleague.context.ContextSource;
import com.mad.edt.colleague.context.KeywordContextSource;
import com.mad.edt.colleague.context.SemanticIndex;
import com.mad.edt.colleague.context.SemanticSettings;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.ui.internal.ChatPanel;
import com.mad.edt.colleague.ui.internal.ChatSession;
import com.mad.edt.colleague.ui.internal.ContactsPanel;
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
    private SemanticIndex ourSemantic;
    private volatile boolean ourOverviewStale;

    private String ourLastAnswer = "";
    private ChatPanel ourLastAnsweredPanel;
    private boolean ourSelfTestDone;
    private boolean ourSelfTestPending;
    private boolean ourBrowserTestDone;
    private boolean ourBrowserTestPending;

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
        ourContacts.setOnAddProvider(this::addProviderWizard);

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
        Job.create("Colleague: project index", (monitor) -> {
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
        }).schedule();

        com.mad.edt.colleague.llm.ProviderStore.loadIntoSettings();
        ourContacts.reload();
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

    private void addProviderWizard() {
        final com.mad.edt.colleague.ui.internal.AddProviderDialog dialog =
            new com.mad.edt.colleague.ui.internal.AddProviderDialog(getSite().getShell());
        if(dialog.open() == org.eclipse.jface.window.Window.OK && dialog.getResult() != null) {
            com.mad.edt.colleague.llm.LLMSettings.register(dialog.getResult());
            com.mad.edt.colleague.llm.ProviderStore.saveFromSettings();
            ourContacts.reload();
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
            return openContact(preferred);
        }
        return null;
    }

    private ContactsPanel.Contact ourDefaultContactCache;

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

    private ChatPanel openContact(final ContactsPanel.Contact theContact) {
        if(ourTabFolder.isDisposed()) {
            return null;
        }
        for(final CTabItem item : ourTabFolder.getItems()) {
            if(theContact.getModel().equals(item.getText())
                && item.getData() instanceof ChatPanel) {
                ourTabFolder.setSelection(item);
                showMobilePage(ourTabsArea);
                return (ChatPanel)item.getData();
            }
        }
        final ChatSession session = ourSessions.computeIfAbsent(theContact.getKey(), key ->
            new ChatSession(
                LLMProviderFactory.create(LLMSettings.getConfig(theContact.getProviderId())),
                SYSTEM_PROMPT));
        final CTabItem tab = new CTabItem(ourTabFolder, SWT.CLOSE);
        tab.setText(theContact.getModel());
        // closing the last chat tab must not leave the empty tab page on
        // screen: the provider list is the only way back into a chat, so the
        // stack falls back to it once the folder runs out of tabs. the close
        // button disposes the item, and the folder itself gives no usable
        // "now empty" event, so the item's own dispose is the trigger
        tab.addDisposeListener((final DisposeEvent theEvent) ->
            Display.getDefault().asyncExec(this::fallBackToContactsIfEmpty));
        // switch the page over before the browser is created: at cold start the
        // preferred contact opens automatically, and a browser created in a
        // hidden zero-sized page paints nothing
        showMobilePage(ourTabsArea);
        final Composite body = new Composite(ourTabFolder, SWT.NONE);
        body.setLayout(new FillLayout());
        final ChatPanel panel = new ChatPanel(body, session, theContact.getModel());
        panel.attachThemeAnchor(ourRoot);
        panel.setToolSupplier(() -> AgentMode.isOn()
            ? WorkspaceTools.withWrite(ourCodeSearch, ourOverview)
            : WorkspaceTools.readOnly(ourCodeSearch, ourOverview));
        panel.setAutoContextSupplier(this::collectAutoContext);
        panel.setOnBack(() -> showMobilePage(ourContactsArea));
        tab.setControl(body);
        tab.setData(panel);
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
        ourTabFolder.setSelection(tab);
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
        final ChatPanel panel = openContact(preferred);
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
