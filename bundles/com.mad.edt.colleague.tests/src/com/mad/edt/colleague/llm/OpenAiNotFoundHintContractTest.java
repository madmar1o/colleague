package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.http.HttpCallResult;

/**
 * Pins the guidance a gateway-style endpoint failure gives the user: an
 * OpenAI-compatible endpoint answers 404 to {@code /chat/completions} whenever
 * the saved address stopped short of the API version segment, which is the
 * single most common misconfiguration. The message must therefore not only
 * quote the failing status but also name the address that would work.
 */
class OpenAiNotFoundHintContractTest {

    private static final String GATEWAY_V1 = "http://127.0.0.1:8080/v1";

    private static ProviderConfig configWithoutVersion(final String theBaseUrl) {
        return ProviderConfig.builder("srata", LLMProviderType.OPENAI_COMPATIBLE)
            .baseUrl(theBaseUrl)
            .readTimeoutMs(1_000)
            .build();
    }

    private static ChatRequest request() {
        return ChatRequest.builder("qwen3.8-flash-next-iq3_s")
            .addMessage(ChatMessage.user("hi"))
            .build();
    }

    @Test
    void streamingNotFoundNamesTheVersionedAddress() {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(404,
            "{\"error\": {\"message\": \"not found\"}}");
        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(configWithoutVersion("http://127.0.0.1:8080"),
                transport);

        final LLMException error = Assertions.assertThrows(LLMException.class,
            () -> provider.chatStream(request(), chunk -> { }, chunk -> { }));
        Assertions.assertTrue(error.getMessage().contains(GATEWAY_V1),
            "the hint must spell out the address the user should have saved: "
                + error.getMessage());
    }

    @Test
    void bufferedNotFoundCarriesTheSameHint() {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(404, "{\"error\": {\"message\": \"not found\"}}"));
        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(configWithoutVersion("http://127.0.0.1:8080/"),
                transport);

        final LLMException error = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(request()));
        // the trailing slash was already trimmed by the configuration itself
        Assertions.assertTrue(error.getMessage().contains(GATEWAY_V1),
            error.getMessage());
    }

    @Test
    void versionedAddressGetsNoHint() {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(404, "{\"error\": {\"message\": \"no such model\"}}"));
        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(
                configWithoutVersion(GATEWAY_V1), transport);

        final LLMException error = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(request()));
        Assertions.assertFalse(error.getMessage().contains("у OpenAI-совместимого"),
            "nothing to hint when the version segment is already there: " + error.getMessage());
    }

    @Test
    void ollamaNotFoundIsNotHintedAboutOpenAiPaths() {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(404, "{\"error\":\"not found\"}"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(
            ProviderConfig.builder("local", LLMProviderType.OLLAMA)
                .baseUrl("http://127.0.0.1:11434")
                .build(),
            transport);

        final LLMException error = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(ChatRequest.builder("m").addMessage(ChatMessage.user("hi"))
                .build()));
        Assertions.assertFalse(error.getMessage().contains("/v1"),
            "the native runtime speaks /api/*, an /v1 hint would mislead: " + error.getMessage());
    }
}
