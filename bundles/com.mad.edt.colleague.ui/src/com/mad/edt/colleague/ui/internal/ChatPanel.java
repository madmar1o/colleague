package com.mad.edt.colleague.ui.internal;

import java.util.function.Consumer;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * One chat conversation tab: history area, question input and status line
 * bound to a dedicated {@link ChatSession}. Extracted from the view so the
 * view shell can host several tabs side by side.
 */
public final class ChatPanel {

    private final ChatSession ourSession;
    private final String ourModel;

    private Text ourConversation;
    private Text ourQuestion;
    private Button ourAskButton;
    private Label ourStatusLabel;

    private String ourAttachedContext = "";
    private String ourLastAnswer = "";
    private boolean ourBusy;
    private Consumer<String> ourAnswerListener;

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

    /** Clears the conversation and the stored session history. */
    public void reset() {
        ourSession.reset();
        if(!ourConversation.isDisposed()) {
            ourConversation.setText("");
        }
        ourLastAnswer = "";
        setStatus("Диалог сброшен");
    }

    /** Releases the SWT controls. */
    public void dispose() {
        if(ourConversation != null && !ourConversation.isDisposed()) {
            ourConversation.dispose();
        }
    }

    private void createControls(final Composite theParent) {
        final Composite root = new Composite(theParent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        ourConversation = new Text(root, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        ourConversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        final Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        final GridLayout headerLayout = new GridLayout(1, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        header.setLayout(headerLayout);
        final Button resetButton = new Button(header, SWT.PUSH);
        resetButton.setText("Новый диалог");
        resetButton.addListener(SWT.Selection, event -> reset());

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
    }

    private void sendQuestion() {
        final String question = ourQuestion.getText().trim();
        if(question.isEmpty() || ourBusy) {
            return;
        }
        final String context = ourAttachedContext;
        ourAttachedContext = "";
        if(!context.isEmpty()) {
            appendLine("Вы: " + question + "  [к вопросу прикреплён контекст проекта, "
                + context.length() + " символов]");
        } else {
            appendLine("Вы: " + question);
        }
        ourQuestion.setText("");
        ourBusy = true;
        ourAskButton.setEnabled(false);
        setStatus("Думаю...");

        Job.create("Colleague: question", (monitor) -> {
            try {
                final String answer = ourSession.ask(ourModel, context, question);
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
}
