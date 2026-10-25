package com.mad.edt.colleague.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;

/**
 * Stateful conversation: keeps the message history and replays it with every
 * new question, attaching the per-turn workspace context and answering with
 * usage statistics appended. Runs on a background thread; not reentrant.
 * This is the conversation logic the chat UI drives, kept free of workbench
 * types so its rules can be pinned by direct tests.
 */
public final class Conversation {

    private static final int MAX_HISTORY_MESSAGES = 40;

    private final ILLMProvider provider;
    private final String systemPrompt;
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * @param theProvider endpoint to talk to
     * @param theSystemPrompt assistant role instructions, may be {@code null}
     */
    public Conversation(final ILLMProvider theProvider, final String theSystemPrompt) {
        provider = theProvider;
        systemPrompt = theSystemPrompt;
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
        return ask(theModel, theContext, theQuestion, chunk -> { });
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
        return ask(theModel, theContext, theQuestion, theOnDelta, chunk -> { });
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
        return ask(theModel, theContext, theQuestion, theOnDelta, theOnThinking, null);
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
        final ChatRequest.Builder request = ChatRequest
            .builder(theModel != null ? theModel : provider.getConfig().getDefaultModel());
        if(systemPrompt != null && !systemPrompt.isEmpty()) {
            request.addMessage(ChatMessage.system(systemPrompt));
        }
        final ChatMessage contextMessage = theContext != null && !theContext.isEmpty()
            ? ChatMessage.system("Workspace context:\n" + theContext) : null;
        for(final ChatMessage message : history) {
            request.addMessage(message);
        }
        if(contextMessage != null) {
            request.addMessage(contextMessage);
        }
        request.addMessage(ChatMessage.user(theQuestion));
        request.think(Boolean.valueOf(wantsVisibleReasoning(theModel)));
        if(theCancellation != null) {
            request.cancellation(theCancellation);
        }

        final long startedAt = System.nanoTime();
        final ChatResult result = provider.chatStream(request.build(), theOnDelta, theOnThinking);
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        if(contextMessage != null) {
            history.add(contextMessage);
        }
        history.add(ChatMessage.user(theQuestion));
        history.add(ChatMessage.assistant(result.getContent()));
        trimHistory();

        return result.getContent()
            + String.format("%n--- %d/%d tokens, %d ms", Integer.valueOf(result.getPromptTokens()),
                Integer.valueOf(result.getCompletionTokens()), Long.valueOf(elapsedMs));
    }

    /**
     * Discards the accumulated conversation.
     */
    public void reset() {
        history.clear();
    }

    /**
     * @return the endpoint this conversation talks to
     */
    public ILLMProvider getProvider() {
        return provider;
    }

    /**
     * @return assistant role instructions, may be {@code null}
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Appends an exchange produced outside {@link #ask} (for example by the
     * agent loop) so follow-up questions keep the memory of it.
     *
     * @param theQuestion user side of the exchange
     * @param theAnswer assistant side of the exchange
     */
    public void noteExchange(final String theQuestion, final String theAnswer) {
        history.add(ChatMessage.user(theQuestion));
        history.add(ChatMessage.assistant(theAnswer));
        trimHistory();
    }

    /**
     * @return a snapshot of the conversation so far, for re-rendering views
     */
    public List<ChatMessage> snapshotHistory() {
        return List.copyOf(history);
    }

    /**
     * Reasoning-capable models produce their chain of thought in a separate
     * stream field; the assistant history keeps only the final answer, so the
     * reasoning pass is requested and shown to the user collapsed apart from
     * the answer.
     *
     * @param theModel model name, may be {@code null}
     * @return {@code true} when the reasoning pass must be requested
     */
    private boolean wantsVisibleReasoning(final String theModel) {
        final String name = theModel == null ? provider.getConfig().getDefaultModel() : theModel;
        return name != null && name.contains("qwen3");
    }

    private void trimHistory() {
        while(history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
    }
}
