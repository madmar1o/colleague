package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the cancellation latch inside a chat request:
 * every request carries a latch, the builder can attach the latch of the
 * owning turn, and attaching is by identity so one stop can end every
 * request of one turn.
 */
class ChatRequestCancellationTest {

    @Test
    @DisplayName("a request without an explicit latch gets a private never-cancelled one")
    void defaultLatch() {
        final ChatRequest request =
            ChatRequest.builder("test-model").addMessage(ChatMessage.user("q")).build();
        Assertions.assertNotNull(request.getCancellation());
        Assertions.assertFalse(request.getCancellation().isCancelled());
    }

    @Test
    @DisplayName("the builder attaches the caller's latch by identity")
    void sharedLatch() {
        final CancellationFlag flag = new CancellationFlag();
        final ChatRequest request = ChatRequest.builder("test-model")
            .addMessage(ChatMessage.user("q"))
            .cancellation(flag)
            .build();
        Assertions.assertSame(flag, request.getCancellation());
        flag.cancel();
        Assertions.assertTrue(request.getCancellation().isCancelled());
    }
}
