package com.mad.edt.colleague.llm;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the answer of the native endpoint to a reasoning request it cannot
 * serve. A model without a thinking capability rejects the whole call with
 * "does not support thinking"; the same model answers the identical prompt
 * once the thinking pass is off. The provider must therefore absorb that
 * rejection itself - one retry without the pass - and remember the model, so
 * the turn never surfaces a transport error the user cannot act on.
 */
class OllamaThinkFallbackContractTest {

    private static ProviderConfig config() {
        return ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test")
            .readTimeoutMs(1_000)
            .build();
    }

    /** @return the rejection the endpoint sends for a non-reasoning model */
    private static HttpCallResult thinkingRejected() {
        return HttpCallResult.ofLines(400, Stream.of(
            "{\"error\":\"\\\"test-model\\\" does not support thinking\"}"));
    }

    /** @param theContent answer text of a normal streamed round */
    private static HttpCallResult answer(final String theContent) {
        return HttpCallResult.ofLines(200, Stream.of(
            "{\"message\":{\"content\":\"" + theContent + "\"},\"done\":true,"
                + "\"done_reason\":\"stop\"}"));
    }

    private static ChatRequest reasoningRequest() {
        return ChatRequest.builder("test-model")
            .addMessage(ChatMessage.user("why?"))
            .think(true)
            .build();
    }

    private static JsonObject body(final RecordingHttpTransport theTransport, final int theIndex) {
        final HttpCallSpec call = theTransport.getCalls().get(theIndex);
        return JsonParser.parseString(call.getJsonBody()).getAsJsonObject();
    }

    @Test
    @DisplayName("a rejected thinking pass is retried once without it and the answer arrives")
    void retriesWithoutTheThinkingPass() throws LLMException {
        final RecordingHttpTransport transport =
            RecordingHttpTransport.sequence(thinkingRejected(), answer("ok"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        final ChatResult result = provider.chatStream(reasoningRequest(), chunk -> { }, chunk -> { });

        Assertions.assertEquals("ok", result.getContent());
        Assertions.assertEquals(2, transport.getCallCount(),
            "the provider must send the repair round itself");
        Assertions.assertTrue(body(transport, 0).get("think").getAsBoolean(),
            "the first round asked for the pass before the endpoint taught otherwise");
        Assertions.assertFalse(body(transport, 1).get("think").getAsBoolean(),
            "the repair round must not ask for thinking again");
        Assertions.assertFalse(provider.supportsThinking("test-model"),
            "the model stays marked as thinking-free for the rest of the session");
    }

    @Test
    @DisplayName("later rounds of the same model skip the thinking pass from the start")
    void keepsLaterRoundsFreeOfTheThinkingPass() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.sequence(
            thinkingRejected(), answer("first"), answer("second"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        provider.chatStream(reasoningRequest(), chunk -> { }, chunk -> { });
        final ChatResult second =
            provider.chatStream(reasoningRequest(), chunk -> { }, chunk -> { });

        Assertions.assertEquals("second", second.getContent());
        Assertions.assertEquals(3, transport.getCallCount());
        Assertions.assertFalse(body(transport, 2).get("think").getAsBoolean(),
            "a learned model must not be asked for thinking a second time");
    }

    @Test
    @DisplayName("a bad request that is not about thinking is not retried")
    void doesNotRetryUnrelatedBadRequests() {
        final RecordingHttpTransport transport = RecordingHttpTransport.sequence(
            HttpCallResult.ofLines(400, Stream.of("{\"error\":\"model not found\"}")),
            answer("never reached"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        Assertions.assertThrows(LLMException.class,
            () -> provider.chatStream(reasoningRequest(), chunk -> { }, chunk -> { }));
        Assertions.assertEquals(1, transport.getCallCount(),
            "a permanent rejection must surface instead of burning a second call");
        Assertions.assertTrue(provider.supportsThinking("test-model"),
            "an unrelated failure says nothing about the model's thinking support");
    }

    /** @param theCapabilities capability names the endpoint reports */
    private static HttpCallResult describes(final String... theCapabilities) {
        final StringBuilder capabilities = new StringBuilder("[");
        for(int i = 0; i < theCapabilities.length; i++) {
            if(i > 0) {
                capabilities.append(',');
            }
            capabilities.append('"').append(theCapabilities[i]).append('"');
        }
        return HttpCallResult.ofBody(200,
            "{\"model_format\":\"gguf\",\"capabilities\":" + capabilities.append(']').append('}'));
    }

    @Test
    @DisplayName("the endpoint's own description of the model answers the thinking question")
    void theModelDescriptionDecidesThinkingSupport() throws LLMException {
        final RecordingHttpTransport transport =
            RecordingHttpTransport.sequence(describes("completion", "tools", "thinking"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        Assertions.assertTrue(provider.supportsThinking("reasoning-model"));
        // cached: the second question costs no second probe
        Assertions.assertTrue(provider.supportsThinking("reasoning-model"));

        Assertions.assertEquals(1, transport.getCallCount(),
            "one description per model is enough for the whole session");
        Assertions.assertEquals("http://runtime.test/api/show",
            transport.getCalls().get(0).getUrl());
        Assertions.assertEquals("reasoning-model",
            body(transport, 0).get("model").getAsString());
    }

    @Test
    @DisplayName("a model the endpoint describes without thinking is never asked for the pass")
    void aDescribedThinkingFreeModelIsNeverAskedForThePass() throws LLMException {
        final RecordingHttpTransport transport =
            RecordingHttpTransport.sequence(describes("completion"), answer("plain answer"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        Assertions.assertFalse(provider.supportsThinking("plain-model"),
            "the description, not the model name, is the source");

        final ChatResult result = provider.chatStream(
            ChatRequest.builder("plain-model").addMessage(ChatMessage.user("hi")).build(),
            chunk -> { }, chunk -> { });

        Assertions.assertEquals("plain answer", result.getContent());
        Assertions.assertEquals(2, transport.getCallCount());
        Assertions.assertFalse(body(transport, 1).has("think"),
            "a request that never asked for thinking must not grow the field on its own");
    }

    @Test
    @DisplayName("an endpoint that cannot describe its model stays on the optimistic answer")
    void anUndescribableModelKeepsTheOptimisticAnswer() {
        final RecordingHttpTransport transport =
            RecordingHttpTransport.sequence(HttpCallResult.ofBody(404, "not found"));
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        Assertions.assertTrue(provider.supportsThinking("older-model"),
            "a failed description probe must not suppress the pass: the round itself "
                + "stays the source of truth");
    }
}
