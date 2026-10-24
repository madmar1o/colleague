package com.mad.edt.colleague.ui.view;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;
import com.mad.edt.colleague.ui.internal.ChatSession;

/**
 * Chat view of the Colleague assistant: model picker, conversation area and
 * a question input. Answers are produced by the core LLM layer.
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

    private Text ourConversation;
    private Text ourQuestion;
    private Combo ourModelCombo;
    private Button ourAskButton;
    private Label ourStatusLabel;

    private ChatSession ourSession;
    private String ourLastAnswer = "";
    private String ourAttachedContext = "";
    private boolean ourBusy;
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
        root.setLayout(new GridLayout(1, false));

        final Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        final GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 4;
        header.setLayout(headerLayout);

        new Label(header, SWT.NONE).setText("Модель:");
        ourModelCombo = new Combo(header, SWT.READ_ONLY | SWT.DROP_DOWN);
        ourModelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        final Button refreshButton = new Button(header, SWT.PUSH);
        refreshButton.setText("Обновить");
        refreshButton.addListener(SWT.Selection, event -> loadModels());

        final Button resetButton = new Button(header, SWT.PUSH);
        resetButton.setText("Новый диалог");
        resetButton.addListener(SWT.Selection, event -> resetConversation());

        ourConversation = new Text(root, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        ourConversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

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

        ourAskButton = new Button(input, SWT.PUSH);
        ourAskButton.setText("Спросить");
        ourAskButton.addListener(SWT.Selection, event -> sendQuestion());

        ourStatusLabel = new Label(root, SWT.NONE);
        ourStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        ourSession = new ChatSession(
            LLMProviderFactory.create(LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID)),
            SYSTEM_PROMPT);

        final String staged = ourPendingQuestion;
        ourPendingQuestion = null;
        if(staged != null && !staged.isEmpty()) {
            ourQuestion.setText(staged);
        }
        final String stagedContext = ourPendingContext;
        ourPendingContext = null;
        if(stagedContext != null && !stagedContext.isEmpty()) {
            ourAttachedContext = stagedContext;
        }

        loadModels();
    }

    @Override
    public void setFocus() {
        if(ourQuestion != null && !ourQuestion.isDisposed()) {
            ourQuestion.setFocus();
        }
    }

    @Override
    public void dispose() {
        if(ourInstance == this) {
            ourInstance = null;
        }
        super.dispose();
    }

    /**
     * @return the answer of the last successful question, never {@code null}
     */
    public String getLastAnswer() {
        return ourLastAnswer;
    }

    /** Applies a question and context staged after this instance was created. */
    public void applyStaged() {
        final String staged = ourPendingQuestion;
        ourPendingQuestion = null;
        if(staged != null && !staged.isEmpty() && ourQuestion != null && !ourQuestion.isDisposed()) {
            ourQuestion.setText(staged);
            setFocus();
        }
        final String stagedContext = ourPendingContext;
        ourPendingContext = null;
        if(stagedContext != null && !stagedContext.isEmpty()) {
            ourAttachedContext = stagedContext;
            setStatus("Контекст объекта прикреплён к вопросу");
        }
    }

    private void loadModels() {
        setStatus("Загружаю список моделей...");
        Job.create("Colleague: model list", (monitor) -> {
            final List<String> models;
            try {
                final ILLMProvider provider = LLMProviderFactory
                    .create(LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID));
                models = provider.listModels().stream()
                    .filter(name -> !name.contains("embed") && !name.startsWith("bge"))
                    .collect(Collectors.toList());
            } catch (final Exception ex) {
                setStatus("Локальный рантайм недоступен: " + ex.getMessage());
                return new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID,
                    "model list failed", ex);
            }
            Display.getDefault().asyncExec(() -> fillModels(models));
            return Status.OK_STATUS;
        }).schedule();
    }

    private void fillModels(final List<String> theModels) {
        if(ourModelCombo.isDisposed()) {
            return;
        }
        ourModelCombo.removeAll();
        for(final String name : theModels) {
            ourModelCombo.add(name);
        }
        final String configured = LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID).getDefaultModel();
        final int preferred = configured == null ? -1 : ourModelCombo.indexOf(configured);
        if(preferred >= 0) {
            ourModelCombo.select(preferred);
        } else if(!theModels.isEmpty()) {
            ourModelCombo.select(0);
        }
        setStatus(theModels.size() + " модель(ей) доступно");
        maybeRunSelfTest();
    }

    private void maybeRunSelfTest() {
        if(!Boolean.getBoolean("colleague.ui.selftest") || ourSelfTestDone) {
            return;
        }
        ourSelfTestDone = true;
        ourSelfTestPending = true;
        ourAttachedContext = "File: demo/Demo.bsl\nContent:\n&НаКлиенте\n"
            + "Процедура ДемонстрацияКонтекста() КонецПроцедуры";
        ourQuestion.setText("Какая процедура описана в приложенном контексте? Ответь одним словом.");
        System.err.println("[colleague] UI selftest: sending question with context");
        sendQuestion();
    }

    private void sendQuestion() {
        final String question = ourQuestion.getText().trim();
        if(question.isEmpty() || ourBusy) {
            return;
        }
        final String model = ourModelCombo.getText().trim();
        final String context = ourAttachedContext;
        ourAttachedContext = "";
        appendLine("Вы: " + (context.isEmpty() ? "" : "[контекст приложен] ") + question);
        ourQuestion.setText("");
        ourBusy = true;
        ourAskButton.setEnabled(false);
        setStatus("Думаю...");

        Job.create("Colleague: question", (monitor) -> {
            try {
                final String answer =
                    ourSession.ask(model.isEmpty() ? null : model, context, question);
                final int split = answer.lastIndexOf("\n--- ");
                final String body = split > 0 ? answer.substring(0, split).strip() : answer;
                final String stats = split > 0 ? answer.substring(split + 1).strip() : "";
                ourLastAnswer = body;
                Display.getDefault().asyncExec(() -> appendLine("Colleague: " + body));
                setStatus(stats);
                if(ourSelfTestPending) {
                    ourSelfTestPending = false;
                    System.err.println("[colleague] UI selftest answer: ["
                        + body.replace('\n', ' ') + "]");
                }
                return Status.OK_STATUS;
            } catch (final Exception ex) {
                Display.getDefault().asyncExec(() -> appendLine("Ошибка: " + ex.getMessage()));
                return new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID,
                    "assistant call failed", ex);
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

    private void resetConversation() {
        if(ourSession != null) {
            ourSession.reset();
        }
        ourConversation.setText("");
        ourLastAnswer = "";
        setStatus("Диалог сброшен");
    }

    private void appendLine(final String theText) {
        if(ourConversation.isDisposed()) {
            return;
        }
        if(ourConversation.getCharCount() > 0) {
            ourConversation.append("\n\n");
        }
        ourConversation.append(theText);
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
