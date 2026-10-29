package com.mad.edt.colleague.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * Contract for a callable language model endpoint. Implementations are
 * stateless and safe for concurrent use.
 */
public interface ILLMProvider {

    /**
     * @return identifier taken from the provider configuration
     */
    String getId();

    /**
     * @return configuration this provider talks to
     */
    ProviderConfig getConfig();

    /**
     * Runs one non-streaming chat completion.
     *
     * @param theRequest the request
     * @return the generated answer
     * @throws LLMException when the endpoint is unreachable, rejects or
     *         returns an unexpected body
     */
    ChatResult chat(ChatRequest theRequest) throws LLMException;

    /**
     * Runs one streaming chat completion: every generated text chunk is
     * handed to the delta consumer as soon as it arrives, the consumer runs
     * on the calling thread. The default implementation has no streaming
     * transport and delivers the whole answer as a single delta.
     *
     * @param theRequest the request
     * @param theOnDelta receives text chunks in generation order
     * @return the generated answer, identical to {@link #chat}
     * @throws LLMException when the endpoint is unreachable, rejects or
     *         returns an unexpected body
     */
    default ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnDelta)
        throws LLMException {
        return chatStream(theRequest, theOnDelta, chunk -> { });
    }

    /**
     * Runs one streaming chat completion separating the model's reasoning
     * pass from the final answer: reasoning chunks go to the thinking
     * consumer, answer chunks to the content consumer. Both run on the
     * calling thread. The default implementation has no streaming transport
     * and delivers the whole answer as a single content delta.
     *
     * @param theRequest the request
     * @param theOnContent receives answer text chunks in generation order
     * @param theOnThinking receives reasoning text chunks, ignored when the
     *            endpoint does not separate reasoning
     * @return the generated answer, identical to {@link #chat}
     * @throws LLMException when the endpoint is unreachable, rejects or
     *         returns an unexpected body
     */
    default ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
        final Consumer<String> theOnThinking) throws LLMException {
        final ChatResult result = chat(theRequest);
        if(!result.getContent().isEmpty()) {
            theOnContent.accept(result.getContent());
        }
        if(result.getThinking() != null && !result.getThinking().isEmpty()) {
            theOnThinking.accept(result.getThinking());
        }
        return result;
    }

    /**
     * Whether the model can run a separate reasoning pass and still answer.
     * A local runtime rejects the whole call for a model without that
     * capability, so the caller must ask before requesting it. A provider
     * that learns the answer from a rejection reports it here.
     *
     * @param theModel model name, {@code null} for the provider default
     * @return {@code true} when the reasoning pass may be requested
     */
    default boolean supportsThinking(final String theModel) {
        return true;
    }

    /**
     * Whether the model completes prompts, i.e. belongs in a list of chat
     * contacts. Endpoints keep other kinds of models - embedding-only ones
     * answer a different call entirely - and describe them, so the list is
     * filtered by that description rather than by name patterns.
     *
     * @param theModel model name, {@code null} for the provider default
     * @return {@code true} when the model may be used for chat
     */
    default boolean supportsChatCapability(final String theModel) {
        return true;
    }

    /**
     * @return model names available at the endpoint
     * @throws LLMException when the endpoint is unreachable or rejects the call
     */
    List<String> listModels() throws LLMException;

    /**
     * Cheap reachability probe used to filter providers in the UI.
     *
     * @return {@code true} when the endpoint answers
     */
    boolean isAvailable();

    /**
     * Asks the endpoint to bring the model into memory without generating a
     * single token, so the next real question starts warm. Endpoints that
     * never evict models (gateway-style) treat this as a no-op.
     *
     * @param theModel model name, {@code null} for the provider default
     * @throws LLMException when the endpoint is reachable but refuses the call
     */
    default void warmModel(final String theModel) throws LLMException {
        // remote endpoints are always warm
    }
}
