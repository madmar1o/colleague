package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the context window contract of the native local endpoint: left alone,
 * the runtime keeps only a tiny window and cuts the oldest prompt text away
 * without a single hint about it. So every chat call must carry an explicit
 * window: at least the configured floor, widened for a prompt that needs
 * more, never past the memory ceiling. The warm-up loads the model with the
 * same window the first real question will use. The gateway dialect never
 * sees any of this. All of it is proven without a server, through the
 * transport seam.
 */
class OllamaContextWindowContractTest {

    private static ProviderConfig local() {
        return ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").readTimeoutMs(1_000).build();
    }

    private static JsonObject bodyOf(final HttpCallSpec theCall) {
        return JsonParser.parseString(theCall.getJsonBody()).getAsJsonObject();
    }

    private static int windowOf(final HttpCallSpec theCall) {
        final JsonObject body = bodyOf(theCall);
        final JsonObject options = body.getAsJsonObject("options");
        Assertions.assertNotNull(options, "chat call must carry options: " + body);
        final JsonElement window = options.get("num_ctx");
        Assertions.assertNotNull(window, "options must carry num_ctx: " + options);
        return window.getAsInt();
    }

    private static ChatRequest askingAbout(final String theText) {
        return ChatRequest.builder("test-model")
            .addMessage(ChatMessage.user(theText)).build();
    }

    @Test
    @DisplayName("a small chat call still carries the configured window floor")
    void chatCarriesWindowFloor() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(local(), transport).chatStream(
            askingAbout("hi"), piece -> { }, piece -> { });

        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS,
            windowOf(transport.getOnlyCall()));
    }

    @Test
    @DisplayName("a prompt bigger than the floor widens the window, capped by the ceiling")
    void bigPromptWidensTheWindowButStaysCapped() throws LLMException {
        // 20k characters is far past what the floor covers once the runtime
        // has tokenized it; 200k characters is past even the ceiling
        final RecordingHttpTransport wide = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(local(), wide).chatStream(
            askingAbout("м".repeat(20_000)), piece -> { }, piece -> { });

        final RecordingHttpTransport huge = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(local(), huge).chatStream(
            askingAbout("м".repeat(200_000)), piece -> { }, piece -> { });

        final int widened = windowOf(wide.getOnlyCall());
        final int capped = windowOf(huge.getOnlyCall());
        Assertions.assertTrue(widened > ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS,
            "a 20k-character prompt must widen the window, got " + widened);
        Assertions.assertTrue(widened < capped,
            "the huge prompt must widen further, got " + widened + " vs " + capped);
        Assertions.assertEquals(ProviderConfig.MAX_LOCAL_CONTEXT_TOKENS, capped,
            "never past the memory ceiling");
    }

    @Test
    @DisplayName("an explicit window override replaces the floor and the ceiling")
    void configuredWindowOverridesTheDefaults() throws LLMException {
        final ProviderConfig roomy = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").readTimeoutMs(1_000)
            .contextWindowTokens(32_768).build();
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(roomy, transport).chatStream(
            askingAbout("hi"), piece -> { }, piece -> { });

        Assertions.assertEquals(32_768, windowOf(transport.getOnlyCall()));
    }

    @Test
    @DisplayName("a non-positive window opts out of the option entirely")
    void windowCanBeTurnedOff() throws LLMException {
        final ProviderConfig plain = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").readTimeoutMs(1_000)
            .contextWindowTokens(0).build();
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(plain, transport).chatStream(
            askingAbout("hi"), piece -> { }, piece -> { });

        final JsonObject options = bodyOf(transport.getOnlyCall())
            .getAsJsonObject("options");
        Assertions.assertTrue(options == null || !options.has("num_ctx"),
            transport.getOnlyCall().getJsonBody());
    }

    @Test
    @DisplayName("warm-up loads the model with the same window the first question will use")
    void warmupCarriesTheSameWindow() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(200, "{\"model\":\"test-model\",\"done\":true}"));
        new OllamaLLMProvider(local(), transport).warmModel("test-model");

        final JsonObject options = bodyOf(transport.getOnlyCall())
            .getAsJsonObject("options");
        Assertions.assertNotNull(options,
            "warm-up must reserve the window up front: " + transport.getOnlyCall().getJsonBody());
        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS,
            options.get("num_ctx").getAsInt());
    }

    @Test
    @DisplayName("the gateway dialect never receives a window option")
    void gatewayNeverSeesTheWindow() throws LLMException {
        final ProviderConfig gateway = ProviderConfig
            .builder("gw", LLMProviderType.OPENAI_COMPATIBLE)
            .baseUrl("http://gw.test").apiKey("k").readTimeoutMs(1_000).build();
        final RecordingHttpTransport transport = RecordingHttpTransport.answering(
            HttpCallResult.ofBody(200,
                "{\"model\":\"gw-model\",\"message\":\"ping\",\"data\":[]}"));
        new OpenAiCompatibleLLMProvider(gateway, transport)
            .warmModel("gw-model");

        Assertions.assertTrue(transport.getCalls().isEmpty(),
            "no warm-up call for a gateway either");
        Assertions.assertEquals(0,
            ProviderConfig.builder("gw2", LLMProviderType.OPENAI_COMPATIBLE)
                .baseUrl("http://gw.test").build().getContextWindowTokens(),
            "a gateway never defaults to a local window");
    }

    @Test
    @DisplayName("the floor sits below the ceiling and both stay inside sane memory bounds")
    void defaultsAreSane() {
        Assertions.assertTrue(ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS
            < ProviderConfig.MAX_LOCAL_CONTEXT_TOKENS);
        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS,
            local().getContextWindowTokens());
        Assertions.assertEquals(ProviderConfig.MAX_LOCAL_CONTEXT_TOKENS,
            local().getContextWindowCeiling());
    }

    @Test
    @DisplayName("a turn cut by the window is known as truncation, a finished one is not")
    void truncationIsReported() throws LLMException {
        // done_reason=length means the runtime ran out of window and dropped
        // the oldest prompt text in silence: the caller can only learn about
        // it through this flag, so the UI can say the answer is incomplete
        final RecordingHttpTransport cut = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"half\"},\"done\":true,\"done_reason\":\"length\"}");
        final ChatResult cutResult = new OllamaLLMProvider(local(), cut)
            .chatStream(askingAbout("hi"), piece -> { }, piece -> { });
        Assertions.assertTrue(cutResult.truncated(),
            "done_reason=length must surface as truncated()");

        final RecordingHttpTransport calm = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true,\"done_reason\":\"stop\"}");
        final ChatResult calmResult = new OllamaLLMProvider(local(), calm)
            .chatStream(askingAbout("hi"), piece -> { }, piece -> { });
        Assertions.assertFalse(calmResult.truncated(),
            "a normally finished answer is not truncated");
    }
}
