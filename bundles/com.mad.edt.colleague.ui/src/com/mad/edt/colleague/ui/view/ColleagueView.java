package com.mad.edt.colleague.ui.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.context.CodeSearch;
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
    private SashForm ourSash;
    private Composite ourContactsArea;
    private Composite ourTabsArea;
    private Composite ourMobileHolder;
    private Composite ourMobileStack;
    private StackLayout ourRootStack;
    private StackLayout ourMobileStackLayout;
    private Button ourBackButton;
    private boolean ourModeMobile;
    private ContactsPanel ourContacts;
    private CTabFolder ourTabFolder;
    private CodeSearch ourCodeSearch;

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
        final Composite root = ourRoot;
        ourRootStack = new StackLayout();
        root.setLayout(ourRootStack);
        ourSash = new SashForm(root, SWT.HORIZONTAL);

        ourContactsArea = new Composite(ourSash, SWT.NONE);
        ourContactsArea.setLayout(new FillLayout());
        ourContacts = new ContactsPanel(ourContactsArea, this::openContact, this::onContactsLoaded);
        ourContacts.setOnAddProvider(this::addProviderWizard);
        ourContacts.setOnModeToggle(this::toggleMode);

        ourTabsArea = new Composite(ourSash, SWT.NONE);
        ourTabsArea.setLayout(new FillLayout());
        ourTabFolder = new CTabFolder(ourTabsArea, SWT.TOP | SWT.FLAT);
        ourTabFolder.setTabHeight(24);

        ourSash.setWeights(1, 2);

        ourMobileHolder = new Composite(root, SWT.NONE);
        final GridLayout mobileLayout = new GridLayout(1, false);
        mobileLayout.marginWidth = 0;
        mobileLayout.marginHeight = 0;
        ourMobileHolder.setLayout(mobileLayout);
        ourBackButton = new Button(ourMobileHolder, SWT.PUSH);
        ourBackButton.setText("← Контакты");
        ourBackButton.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        ourBackButton.addListener(SWT.Selection, event -> showMobilePage(ourContactsArea));
        ourBackButton.setVisible(false);
        final Composite contentRow = new Composite(ourMobileHolder, SWT.NONE);
        contentRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        contentRow.setLayout(new FillLayout());
        ourMobileStack = new Composite(contentRow, SWT.NONE);
        ourMobileStackLayout = new StackLayout();
        ourMobileStack.setLayout(ourMobileStackLayout);

        ourCodeSearch = new CodeSearch();
        Job.create("Colleague: project index", (monitor) -> {
            final int files = ourCodeSearch.reindex(monitor);
            System.err.println("[colleague] context index ready: " + files + " files");
            if(Boolean.getBoolean("colleague.search.selftest")) {
                final long startedAt = System.nanoTime();
                final String found = ourCodeSearch.search("модуль объекта справочник проводка");
                final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                System.err.println("[colleague] search selftest: " + found.length()
                    + " chars in " + elapsedMs + " ms, first line: "
                    + found.lines().findFirst().orElse("<none>"));
            }
            return Status.OK_STATUS;
        }).schedule();

        ourRootStack.topControl = ourSash;
        ourModeMobile = modePreference();
        if(ourModeMobile) {
            applyMode(true);
        }
        com.mad.edt.colleague.llm.ProviderStore.loadIntoSettings();
        ourContacts.reload();
    }

    private static boolean modePreference() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
            .getNode("com.mad.edt.colleague.ui").getBoolean("ui.mobile", false);
    }

    private static void saveModePreference(final boolean theMobile) {
        final org.osgi.service.prefs.Preferences node =
            org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                .getNode("com.mad.edt.colleague.ui");
        node.putBoolean("ui.mobile", theMobile);
        try {
            node.flush();
        } catch (final org.osgi.service.prefs.BackingStoreException ex) {
            // in-memory value is enough for this session
        }
    }

    private void toggleMode() {
        ourModeMobile = !ourModeMobile;
        saveModePreference(ourModeMobile);
        applyMode(ourModeMobile);
    }

    /** Re-parents the shared panels between the desktop sash and mobile stack. */
    private void applyMode(final boolean theMobile) {
        if(ourTabFolder.isDisposed()) {
            return;
        }
        if(theMobile) {
            ourContactsArea.setParent(ourMobileStack);
            ourTabsArea.setParent(ourMobileStack);
            ourRootStack.topControl = ourMobileHolder;
            ourMobileStackLayout.topControl = ourContactsArea;
            ourBackButton.setVisible(false);
            showMobilePage(ourContactsArea);
        } else {
            ourContactsArea.setParent(ourSash);
            ourTabsArea.setParent(ourSash);
            ourSash.setWeights(1, 2);
            ourRootStack.topControl = ourSash;
            ourBackButton.setVisible(false);
        }
        ourSash.layout(true);
        ourMobileHolder.layout(true);
        // the root composite owns the StackLayout: changing topControl only
        // takes effect once its own layout has been re-run
        ourRoot.layout(true);
        if(ourContacts != null) {
            ourContacts.setModeLabel(ourModeMobile ? "Десктоп" : "Мобильный");
        }
    }

    private void showMobilePage(final Composite thePage) {
        if(ourModeMobile && !thePage.isDisposed()) {
            ourMobileStackLayout.topControl = thePage;
            ourBackButton.setVisible(thePage == ourTabsArea);
            ourMobileStack.layout(true);
            ourBackButton.getParent().layout(true);
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

    private ChatPanel openContact(final ContactsPanel.Contact theContact) {
        if(ourTabFolder.isDisposed()) {
            return null;
        }
        for(final CTabItem item : ourTabFolder.getItems()) {
            if(theContact.getModel().equals(item.getText())
                && item.getData() instanceof ChatPanel) {
                ourTabFolder.setSelection(item);
                if(ourModeMobile) {
                    showMobilePage(ourTabsArea);
                }
                return (ChatPanel)item.getData();
            }
        }
        final ChatSession session = ourSessions.computeIfAbsent(theContact.getKey(), key ->
            new ChatSession(
                LLMProviderFactory.create(LLMSettings.getConfig(theContact.getProviderId())),
                SYSTEM_PROMPT));
        final CTabItem tab = new CTabItem(ourTabFolder, SWT.CLOSE);
        tab.setText(theContact.getModel());
        final Composite body = new Composite(ourTabFolder, SWT.NONE);
        body.setLayout(new FillLayout());
        final ChatPanel panel = new ChatPanel(body, session, theContact.getModel());
        panel.attachThemeAnchor(ourRoot);
        panel.setToolSupplier(() -> AgentMode.isOn()
            ? WorkspaceTools.withWrite(ourCodeSearch)
            : WorkspaceTools.readOnly(ourCodeSearch));
        panel.setAutoContextSupplier(theQuestion ->
            ourCodeSearch == null ? "" : ourCodeSearch.search(theQuestion));
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
        if(ourModeMobile) {
            showMobilePage(ourTabsArea);
        }
        return panel;
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
