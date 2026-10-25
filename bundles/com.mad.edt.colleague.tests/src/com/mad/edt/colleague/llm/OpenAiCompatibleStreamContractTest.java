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
 * Pins the frame contract of the HTTP chat-completions streaming dialect:
 * server-sent events with {@code data:} prefixes, reasoning carried in
 * {@code delta.reasoning_content}, ordinary text in {@code delta.content},
 * the {@code [DONE]} sentinel skipped, usage taken from the final frame.
 */
class OpenAiCompatibleStreamContractTest {

    private static ProviderConfig config() {
        return ProviderConfig.builder("test", LLMProviderType.OPENAI_COMPATIBLE)
            .baseUrl("http://gateway.test/v1")
            .readTimeoutMs(1_000)
            .build();
    }

    @Test
    @DisplayName("reasoning deltas and content deltas are kept apart")
    void reasoningAndContentAreSplit() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "data: {\"model\":\"gw-x\",\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm \"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"alright\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\" there\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}",
            "data: [DONE]");
        final List<String> thinkingFrames = new ArrayList<>();
        final List<String> contentFrames = new ArrayList<>();

        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(config(), transport);
        final ChatResult result = provider.chatStream(
            ChatRequest.builder("gw-model").addMessage(ChatMessage.user("hi")).build(),
            contentFrames::add, thinkingFrames::add);

        Assertions.assertEquals(List.of("hmm ", "alright"), thinkingFrames);
        Assertions.assertEquals(List.of("Hi", " there"), contentFrames);
        Assertions.assertEquals("Hi there", result.getContent());
        Assertions.assertEquals("hmm alright", result.getThinking());
        Assertions.assertEquals("gw-x", result.getModel());
        Assertions.assertEquals("stop", result.getFinishReason());
        Assertions.assertEquals(2, result.getPromptTokens());
        Assertions.assertEquals(3, result.getCompletionTokens());
        Assertions.assertTrue(transport.isStreamClosed());
    }

    @Test
    @DisplayName("the stream goes to chat/completions under the versioned base URL")
    void requestShape() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]");
        final OpenAiCompatibleLLMProvider provider =
            new OpenAiCompatibleLLMProvider(config(), transport);
        provider.chatStream(ChatRequest.builder("gw-model")
            .addMessage(ChatMessage.user("ping")).build(), piece -> { }, piece -> { });

        final HttpCallSpec call = transport.getOnlyCall();
        Assertions.assertEquals("http://gateway.test/v1/chat/completions", call.getUrl());
        final JsonObject body = JsonParser.parseString(call.getJsonBody()).getAsJsonObject();
        Assertions.assertTrue(body.get("stream").getAsBoolean());
        Assertions.assertEquals("gw-model", body.get("model").getAsString());
    }
}
