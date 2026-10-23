package com.mad.edt.colleague.llm;

/**
 * Creates a provider instance for the protocol described by a configuration.
 */
public final class LLMProviderFactory {

    private LLMProviderFactory() {
        // utility
    }

    /**
     * @param theConfig endpoint configuration
     * @return provider for the configured protocol
     */
    public static ILLMProvider create(final ProviderConfig theConfig) {
        switch(theConfig.getType()) {
            case OLLAMA:
                return new OllamaLLMProvider(theConfig);
            case OPENAI_COMPATIBLE:
                return new OpenAiCompatibleLLMProvider(theConfig);
            default:
                throw new IllegalArgumentException("Unknown provider type " + theConfig.getType());
        }
    }
}
