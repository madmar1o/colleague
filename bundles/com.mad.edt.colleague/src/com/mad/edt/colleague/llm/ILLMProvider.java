package com.mad.edt.colleague.llm;

import java.util.List;

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
}
