package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.TransportFailure;

/**
 * Pins how provider failures surface: HTTP error statuses keep their code and
 * an excerpt, unreachable endpoints and unparsable bodies become
 * {@link LLMException}, and a stop in the middle of a stream keeps the text
 * produced so far while releasing the connection.
 */
class ProviderFailureContractTest {

    private static final String FIRST = "first";

    private static ProviderConfig config() {
        return ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test")
            .readTimeoutMs(1_000)
            .build();
    }

    private static ChatRequest request(final CancellationFlag theFlag) {
        return ChatRequest.builder("test-model")
            .addMessage(ChatMessage.user("q"))
            .cancellation(theFlag)
            .build();
    }

    @Test
    @DisplayName("a buffered error status surfaces with code and excerpt")
    void bufferedHttpError() {
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(),
            RecordingHttpTransport.answering(HttpCallResult.ofBody(404, "model not found")));
        final LLMException failure = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(request(null)));
        Assertions.assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
        Assertions.assertTrue(failure.getMessage().contains("model not found"),
            failure.getMessage());
    }

    @Test
    @DisplayName("a streaming error status surfaces with code and first lines")
    void streamingHttpError() {
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(),
            RecordingHttpTransport.streaming(500, "engine busy", "try later"));
        final LLMException failure = Assertions.assertThrows(LLMException.class,
            () -> provider.chatStream(request(null), piece -> { }, piece -> { }));
        Assertions.assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        Assertions.assertTrue(failure.getMessage().contains("engine busy"), failure.getMessage());
    }

    @Test
    @DisplayName("an unreachable endpoint becomes an LLMException, not an IO detail")
    void unreachableEndpoint() {
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(),
            RecordingHttpTransport.failing(new TransportFailure("cannot reach runtime")));
        final LLMException failure = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(request(null)));
        Assertions.assertTrue(failure.getMessage().contains("cannot reach runtime"),
            failure.getMessage());
    }

    @Test
    @DisplayName("a non-JSON body is reported as unexpected, with an excerpt")
    void nonJsonBody() {
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(),
            RecordingHttpTransport.answering(HttpCallResult.ofBody(200, "<<html>>")));
        final LLMException failure = Assertions.assertThrows(LLMException.class,
            () -> provider.chat(request(null)));
        Assertions.assertTrue(failure.getMessage().contains("non-JSON"), failure.getMessage());
    }

    @Test
    @DisplayName("a stop in the middle keeps produced text and closes the stream")
    void stopMidStreamKeepsPartialText() throws LLMException {
        final RecordingHttpTransport transport = RecordingHttpTransport.streaming(200,
            "{\"message\":{\"thinking\":\"pondering\"}}",
            "{\"message\":{\"content\":\"first\"}}",
            "{\"message\":{\"content\":\"NEVER DELIVERED\"}}",
            "{\"message\":{\"content\":\"ALSO NEVER\"}}");
        final CancellationFlag flag = new CancellationFlag();
        final List<String> contentFrames = new ArrayList<>();
        final OllamaLLMProvider provider = new OllamaLLMProvider(config(), transport);

        final ChatResult result = provider.chatStream(request(flag), piece -> {
            contentFrames.add(piece);
            if(FIRST.equals(piece)) {
                flag.cancel();
            }
        }, piece -> { });

        Assertions.assertEquals(List.of(FIRST), contentFrames);
        Assertions.assertEquals(FIRST, result.getContent());
        Assertions.assertTrue(transport.isStreamClosed(),
            "stopping a turn must release the connection");
    }
}
