package com.mad.edt.colleague.ui.internal;

import java.util.List;
import java.util.function.Consumer;

import com.mad.edt.colleague.chat.Conversation;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;

/**
 * UI-side handle onto one contact's conversation. The conversation rules -
 * history replay, context placement, usage statistics - live in the core
 * {@link Conversation}, covered by its own tests; this shim only keeps the
 * workbench-facing type and hands everything through to the delegate.
 */
public final class ChatSession {

    private final Conversation conversation;

    /**
     * @param theProvider endpoint to talk to
     * @param theSystemPrompt assistant role instructions, may be {@code null}
     */
    public ChatSession(final ILLMProvider theProvider, final String theSystemPrompt) {
        conversation = new Conversation(theProvider, theSystemPrompt);
    }

    /**
     * Asks the model one question with surrounding project context attached,
     * and appends both sides to the history.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion)
        throws LLMException {
        return conversation.ask(theModel, theContext, theQuestion);
    }

    /**
     * Asks the model with a live feed of generated text chunks.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta) throws LLMException {
        return conversation.ask(theModel, theContext, theQuestion, theOnDelta);
    }

    /**
     * Asks the model with a live feed of generated text, keeping the model's
     * reasoning pass on a separate channel so the caller can present it apart
     * from the final answer.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @param theOnThinking receives reasoning chunks as they are generated
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta, final Consumer<String> theOnThinking)
        throws LLMException {
        return conversation.ask(theModel, theContext, theQuestion, theOnDelta, theOnThinking);
    }

    /**
     * Asks the model with the cancellation latch of the caller's turn wired
     * into the request: stopping the turn stops the streamed read loop.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @param theOnThinking receives reasoning chunks as they are generated
     * @param theCancellation shared stop flag, {@code null} for none
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta, final Consumer<String> theOnThinking,
        final CancellationFlag theCancellation) throws LLMException {
        return conversation.ask(theModel, theContext, theQuestion, theOnDelta, theOnThinking,
            theCancellation);
    }

    /**
     * Discards the accumulated conversation.
     */
    public void reset() {
        conversation.reset();
    }

    /**
     * @return the endpoint this session talks to
     */
    public ILLMProvider getProvider() {
        return conversation.getProvider();
    }

    /**
     * @return assistant role instructions, may be {@code null}
     */
    public String getSystemPrompt() {
        return conversation.getSystemPrompt();
    }

    /**
     * Appends an exchange produced outside {@link #ask} (for example by the
     * agent loop) so follow-up questions keep the memory of it.
     *
     * @param theQuestion user side of the exchange
     * @param theAnswer assistant side of the exchange
     */
    public void noteExchange(final String theQuestion, final String theAnswer) {
        conversation.noteExchange(theQuestion, theAnswer);
    }

    /**
     * @return a snapshot of the conversation so far, for re-rendering views
     */
    public List<ChatMessage> snapshotHistory() {
        return conversation.snapshotHistory();
    }
}
