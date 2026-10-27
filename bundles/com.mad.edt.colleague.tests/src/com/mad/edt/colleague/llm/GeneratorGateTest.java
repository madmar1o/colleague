package com.mad.edt.colleague.llm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ownership of one local generator. On a small GPU one model at a time fits:
 * a background embedding pass that keeps hammering the model server while a
 * chat or agent turn runs evicts the chat model and every step of the turn
 * pays for loading it back. Background work must therefore ask the gate for
 * quiet before touching the generator, and the gate only goes quiet once the
 * last holder released it.
 */
class GeneratorGateTest {

    @Test
    @DisplayName("a fresh gate is idle")
    void freshGateIdle() {
        Assertions.assertTrue(new GeneratorGate().isIdle());
    }

    @Test
    @DisplayName("nested holders keep the gate busy until the last leaves")
    void nestedHolders() {
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        gate.enter();
        gate.leave();
        Assertions.assertFalse(gate.isIdle(), "one holder is still inside");
        gate.leave();
        Assertions.assertTrue(gate.isIdle());
    }

    @Test
    @DisplayName("leaving without entering never opens a negative hole")
    void leaveFloorsAtIdle() {
        final GeneratorGate gate = new GeneratorGate();
        gate.leave();
        Assertions.assertTrue(gate.isIdle());
    }

    @Test
    @DisplayName("awaitIdle waits out a short burst and reports quiet")
    void awaitWaitsOutShortBurst() throws Exception {
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        final Thread holder = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ignored) {
                // ending the burst early is fine either way
            }
            gate.leave();
        });
        holder.start();
        Assertions.assertTrue(gate.awaitIdle(5_000L),
            "the wait must cover a burst that ends on its own");
        holder.join();
    }

    @Test
    @DisplayName("awaitIdle times out while a holder keeps occupying")
    void awaitTimesOutOnStuckHolder() {
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        Assertions.assertFalse(gate.awaitIdle(80L),
            "a bounded wait keeps a background pass from hanging forever");
        gate.leave();
        Assertions.assertTrue(gate.awaitIdle(0L));
    }

    @Test
    @DisplayName("an interrupted wait hands the interrupt back as refusal")
    void awaitHonoursInterrupt() throws Exception {
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread waiter = new Thread(() -> {
            waiting.countDown();
            Assertions.assertFalse(gate.awaitIdle(30_000L),
                "an interrupted wait must not pretend the gate went quiet");
            Assertions.assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt belongs to the caller, the gate must not swallow it");
        });
        waiter.setUncaughtExceptionHandler((thread, ex) -> failure.set(ex));
        waiter.start();
        Assertions.assertTrue(waiting.await(5L, TimeUnit.SECONDS), "waiter never got in");
        // let the wait actually enter its sleep before cutting it short
        Thread.sleep(120L);
        waiter.interrupt();
        waiter.join(5_000L);
        gate.leave();
        Assertions.assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }
}
