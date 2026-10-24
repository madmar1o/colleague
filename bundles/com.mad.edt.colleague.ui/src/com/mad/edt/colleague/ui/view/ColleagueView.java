package com.mad.edt.colleague.ui.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.ui.internal.ChatPanel;
import com.mad.edt.colleague.ui.internal.ChatSession;
import com.mad.edt.colleague.ui.internal.ContactsPanel;

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

    private SashForm ourSash;
    private ContactsPanel ourContacts;
    private CTabFolder ourTabFolder;

    private String ourLastAnswer = "";
    private ChatPanel ourLastAnsweredPanel;
    private boolean ourSelfTestDone;
    private boolean ourSelfTestPending;

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
        final Composite root = new Composite(theParent, SWT.NONE);
        root.setLayout(new FillLayout());
        ourSash = new SashForm(root, SWT.HORIZONTAL);

        final Composite contactsArea = new Composite(ourSash, SWT.NONE);
        contactsArea.setLayout(new FillLayout());
        ourContacts = new ContactsPanel(contactsArea, this::openContact, this::onContactsLoaded);

        final Composite tabsArea = new Composite(ourSash, SWT.NONE);
        tabsArea.setLayout(new FillLayout());
        ourTabFolder = new CTabFolder(tabsArea, SWT.TOP | SWT.FLAT);
        ourTabFolder.setTabHeight(24);

        ourSash.setWeights(1, 2);
        ourContacts.reload();
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
        final String configured =
            LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID).getDefaultModel();
        for(final ContactsPanel.Contact contact : theAvailable) {
            if(contact.getProviderId().equals(LLMSettings.LOCAL_PROVIDER_ID)
                && contact.getModel().equals(configured)) {
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
        });
        ourTabFolder.setSelection(tab);
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
