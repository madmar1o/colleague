package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.CancellationFlag;

/**
 * Pins the turn bookkeeping the stop button depends on: a started turn owns
 * one cancellation latch and one worker thread; aborting fires the latch and
 * interrupts the worker; starting a new turn makes the previous one stale so
 * its late results are dropped instead of overwriting the new answer.
 */
class TurnControllerTest {

    @Test
    void aStartedTurnCarriesAFreshLatchAndIsCurrent() {
        final TurnController controller = new TurnController();
        final TurnController.Turn turn = controller.startTurn();
        assertFalse(turn.cancellation().isCancelled());
        assertTrue(controller.isCurrent(turn));
        assertFalse(controller.isAborted());
    }

    @Test
    void abortFiresTheLatchOfTheActiveTurnOnly() {
        final TurnController controller = new TurnController();
        final TurnController.Turn first = controller.startTurn();
        assertTrue(controller.abortTurn());
        assertTrue(first.cancellation().isCancelled());
        assertTrue(controller.isAborted());
        assertTrue(controller.stopLatencyMs() >= 0L);
    }

    @Test
    void abortWithoutAnyTurnIsRefusedAndLeavesNothingAborted() {
        final TurnController controller = new TurnController();
        assertFalse(controller.abortTurn());
        assertFalse(controller.isAborted());
    }

    @Test
    void abortInterruptsTheAttachedWorker() throws Exception {
        final TurnController controller = new TurnController();
        final TurnController.Turn turn = controller.startTurn();
        final CancellationFlag seen = new CancellationFlag();
        final Thread worker = new Thread(() -> {
            try {
                Thread.sleep(30_000L);
            } catch (final InterruptedException ex) {
                seen.cancel();
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        controller.attachWorker(turn, worker);
        assertTrue(controller.abortTurn());
        worker.join(5_000L);
        assertFalse(worker.isAlive(), "the worker must stop being blocked");
        assertTrue(seen.isCancelled(), "the interrupt must reach the worker");
    }

    @Test
    void aReplacedTurnIsStaleAndCannotBeInterruptedThroughIt() {
        final TurnController controller = new TurnController();
        final TurnController.Turn first = controller.startTurn();
        final TurnController.Turn second = controller.startTurn();
        assertNotEquals(first, second);
        assertFalse(controller.isCurrent(first));
        assertTrue(controller.isCurrent(second));
        // a late abort from the stale turn's UI path hits the CURRENT turn's
        // latch, exactly like today's single shared cancel handle
        assertTrue(controller.abortTurn());
        assertTrue(second.cancellation().isCancelled());
        assertFalse(first.cancellation().isCancelled());
        // starting yet another turn clears the aborted mark
        final TurnController.Turn third = controller.startTurn();
        assertFalse(controller.isAborted());
        assertTrue(controller.isCurrent(third));
    }
}
