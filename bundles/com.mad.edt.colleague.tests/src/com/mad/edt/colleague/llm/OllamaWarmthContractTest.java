package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the runtime warmth contract of the native local endpoint: every chat
 * call tells the endpoint how long the model must stay loaded after the call,
 * and a warm-up call loads the model without generating a single token. The
 * gateway-style dialect has neither: remote endpoints are always warm. All of
 * this is proven without a server, through the transport seam.
 */
class OllamaWarmthContractTest {

    private static ProviderConfig local() {
        return ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").readTimeoutMs(1_000).build();
    }

    private static ProviderConfig gateway() {
        return ProviderConfig.builder("gw", LLMProviderType.OPENAI_COMPATIBLE)
            .baseUrl("http://gw.test").apiKey("k").readTimeoutMs(1_000).build();
    }

    private static JsonObject bodyOf(final HttpCallSpec theCall) {
        return JsonParser.parseString(theCall.getJsonBody()).getAsJsonObject();
    }

    @Test
    @DisplayName("a default local chat call carries the residence lifetime")
    void chatCarriesKeepAlive() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(local(), transport).chatStream(
            ChatRequest.builder("test-model").addMessage(ChatMessage.user("hi")).build(),
            piece -> { }, piece -> { });

        final JsonObject body = bodyOf(transport.getOnlyCall());
        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_KEEP_ALIVE,
            body.get("keep_alive").getAsString());
    }

    @Test
    @DisplayName("an empty lifetime opts the residence claim out of both calls")
    void keepAliveCanBeTurnedOff() throws LLMException {
        final ProviderConfig plain = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").readTimeoutMs(1_000).keepAlive("").build();
        final RecordingHttpTransport chatTransport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(plain, chatTransport).chatStream(
            ChatRequest.builder("test-model").addMessage(ChatMessage.user("hi")).build(),
            piece -> { }, piece -> { });
        final RecordingHttpTransport warmTransport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(200, "{\"model\":\"test-model\",\"done\":true}"));
        new OllamaLLMProvider(plain, warmTransport).warmModel("test-model");

        Assertions.assertFalse(bodyOf(chatTransport.getOnlyCall()).has("keep_alive"),
            chatTransport.getOnlyCall().getJsonBody());
        Assertions.assertFalse(bodyOf(warmTransport.getOnlyCall()).has("keep_alive"),
            warmTransport.getOnlyCall().getJsonBody());
    }

    @Test
    @DisplayName("warm-up posts to /api/generate without a prompt: model loads, no token is generated")
    void warmupLoadsWithoutGenerating() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(200, "{\"model\":\"test-model\",\"done\":true}"));
        new OllamaLLMProvider(local(), transport).warmModel("test-model");

        final HttpCallSpec call = transport.getOnlyCall();
        Assertions.assertEquals("http://runtime.test/api/generate", call.getUrl());
        final JsonObject body = bodyOf(call);
        Assertions.assertEquals("test-model", body.get("model").getAsString());
        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_KEEP_ALIVE,
            body.get("keep_alive").getAsString());
        Assertions.assertEquals(Boolean.FALSE, body.get("stream").getAsBoolean(),
            "warm-up must answer with one buffered JSON document");
        Assertions.assertFalse(body.has("prompt"),
            "warm-up must not ask for any generated text: " + call.getJsonBody());
    }

    @Test
    @DisplayName("the gateway dialect is never asked to keep anything resident and never warms up")
    void gatewayHasNoWarmthSurface() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.answering(
            HttpCallResult.ofBody(200,
                "{\"model\":\"gw-model\",\"message\":\"ping\",\"data\":[]}"));
        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(gateway(), transport);
        provider.warmModel("gw-model");

        Assertions.assertTrue(transport.getCalls().isEmpty(), "no warm-up call for a gateway");
        Assertions.assertTrue(local().getKeepAlive() != null
            && gateway().getKeepAlive() == null);
    }

    @Test
    @DisplayName("a rejected warm-up surfaces as a provider failure to the caller")
    void warmupFailureSurfaces() {
        final RecordingHttpTransport transport = RecordingHttpTransport
            .answering(HttpCallResult.ofBody(500, "model file missing"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(local(), transport);
        final LLMException failure =
            Assertions.assertThrows(LLMException.class, () -> provider.warmModel("test-model"));
        Assertions.assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
    }

    @Test
    @DisplayName("a residence claim carries a renewal budget strictly shorter than itself")
    void claimCarriesRenewalBudget() {
        // the default 30-minute claim must be renewed while the model is still loaded
        Assertions.assertEquals(1_800_000, local().keepAliveLifetimeMs());
        Assertions.assertEquals(600_000, local().warmRenewalDelayMs());
        Assertions.assertTrue(local().warmRenewalDelayMs() < local().keepAliveLifetimeMs());

        Assertions.assertEquals(180_000,
            ProviderConfig.builder("t", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test").keepAlive("3m").build()
                .keepAliveLifetimeMs());
        Assertions.assertEquals(900_000,
            ProviderConfig.builder("t", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test").keepAlive("15m").build()
                .keepAliveLifetimeMs());

        // nothing to renew: gateway, opted-out, forever or unparseable claims
        Assertions.assertEquals(-1, gateway().keepAliveLifetimeMs());
        Assertions.assertEquals(-1, gateway().warmRenewalDelayMs());
        Assertions.assertEquals(-1, ProviderConfig.builder("t", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").keepAlive("").build().keepAliveLifetimeMs());
        Assertions.assertEquals(-1, ProviderConfig.builder("t", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").keepAlive("-1").build().keepAliveLifetimeMs());
        Assertions.assertEquals(-1, ProviderConfig.builder("t", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").keepAlive("soon").build().keepAliveLifetimeMs());
    }
}
