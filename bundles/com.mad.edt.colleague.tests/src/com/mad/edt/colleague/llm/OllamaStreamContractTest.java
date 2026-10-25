package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the frame contract of the native streaming chat endpoint: thinking
 * frames and content frames must reach different callbacks in order, broken
 * frames are skipped, the stop frame fills usage counters, and the request
 * body carries the streaming switch. All of this is proven without a server,
 * through the transport seam.
 */
class OllamaStreamContractTest {

    private static ProviderConfig config() {
        return ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test")
            .readTimeoutMs(1_000)
            .build();
    }

    @Test
    @DisplayName("thinking and content frames reach separate callbacks in order")
    void thinkingAndContentAreSplit() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"thinking\":\"\"}}",
            "{\"message\":{\"thinking\":\"Why \"}}",
            "{\"message\":{\"thinking\":\"it works\"}}",
            "this line is not JSON at all",
            "{\"message\":{\"content\":\"Because\"}}",
            "{\"message\":{\"content\":\".\"},\"done\":true,\"done_reason\":\"stop\","
                + "\"prompt_eval_count\":11,\"eval_count\":5}");
        final List<String> thinkingFrames = new ArrayList<>();
        final List<String> contentFrames = new ArrayList<>();

        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);
        final ChatResult result = provider.chatStream(
            ChatRequest.builder("test-model").addMessage(ChatMessage.user("why?")).build(),
            contentFrames::add, thinkingFrames::add);

        Assertions.assertEquals(List.of("Why ", "it works"), thinkingFrames);
        Assertions.assertEquals(List.of("Because", "."), contentFrames);
        Assertions.assertEquals("Because.", result.getContent());
        Assertions.assertEquals("Why it works", result.getThinking());
        Assertions.assertEquals("test-model", result.getModel());
        Assertions.assertEquals("stop", result.getFinishReason());
        Assertions.assertEquals(11, result.getPromptTokens());
        Assertions.assertEquals(5, result.getCompletionTokens());
        Assertions.assertTrue(transport.isStreamClosed(),
            "the line stream must be closed even on a normal end of stream");
    }

    @Test
    @DisplayName("the streaming request goes to /api/chat with the stream switch on")
    void requestShape() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);
        provider.chatStream(ChatRequest.builder("test-model")
            .addMessage(ChatMessage.user("hi")).build(), piece -> { }, piece -> { });

        final HttpCallSpec call = transport.getOnlyCall();
        Assertions.assertEquals("http://runtime.test/api/chat", call.getUrl());
        Assertions.assertEquals("POST", call.getMethod());
        final JsonObject body = JsonParser.parseString(call.getJsonBody()).getAsJsonObject();
        Assertions.assertTrue(body.get("stream").getAsBoolean());
        Assertions.assertEquals("test-model", body.get("model").getAsString());
        final JsonObject message = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        Assertions.assertEquals("user", message.get("role").getAsString());
        Assertions.assertEquals("hi", message.get("content").getAsString());
    }

    @Test
    @DisplayName("an configured api key rides along as a bearer header")
    void apiKeyHeader() throws LLMException {
        final ProviderConfig withKey = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test").apiKey("secret-key").readTimeoutMs(1_000).build();
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"content\":\"ok\"},\"done\":true}");
        new OllamaLLMProvider(withKey, transport)
            .chatStream(ChatRequest.builder("test-model")
                .addMessage(ChatMessage.user("hi")).build(), piece -> { }, piece -> { });
        Assertions.assertEquals("Bearer secret-key",
            transport.getOnlyCall().getHeaders().get("Authorization"));
    }
}
