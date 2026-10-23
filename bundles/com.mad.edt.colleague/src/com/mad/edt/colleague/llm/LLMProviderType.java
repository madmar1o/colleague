package com.mad.edt.colleague.llm;

/**
 * Protocol spoken by a provider endpoint.
 */
public enum LLMProviderType {

    /** Native local model runtime with {@code /api/chat} endpoints. */
    OLLAMA,

    /** Any HTTP endpoint compatible with the OpenAI chat-completions contract. */
    OPENAI_COMPATIBLE
}
