package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the shared cancellation latch: the contract
 * between the UI thread that stops a turn and the provider loops that poll
 * the latch between streamed frames.
 */
class CancellationFlagTest {

    @Test
    @DisplayName("a fresh latch is not cancelled")
    void fresh() {
        Assertions.assertFalse(new CancellationFlag().isCancelled());
    }

    @Test
    @DisplayName("cancel is sticky and idempotent")
    void sticky() {
        final CancellationFlag flag = new CancellationFlag();
        flag.cancel();
        flag.cancel();
        Assertions.assertTrue(flag.isCancelled());
    }

    @Test
    @DisplayName("cancellation is visible to the polling thread")
    void crossThread() throws InterruptedException {
        final CancellationFlag flag = new CancellationFlag();
        final Thread poller = new Thread(() -> {
            while(!flag.isCancelled()) {
                Thread.onSpinWait();
            }
        });
        poller.start();
        flag.cancel();
        poller.join(5_000L);
        Assertions.assertFalse(poller.isAlive(),
            "the polling loop must observe cancel() across threads");
    }
}
