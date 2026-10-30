package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Pins the thread contract of the bridge push ticker: the page may only be
 * entered from the UI thread, while a push can be queued from any thread (the
 * answer job, the stop path, the status line all run on workers). A tick from
 * the UI thread goes straight to the timer; a tick from a worker first hops
 * onto the UI thread and only then asks the timer, so the widget call never
 * happens on the foreign thread.
 */
class UiTicksTest {

    /** records the two hops the dispatcher may take */
    private static final class Hops {

        private final List<Runnable> async = new ArrayList<>();
        private final List<Runnable> timer = new ArrayList<>();
        private final AtomicBoolean onUiThread = new AtomicBoolean(true);

        UiTicks create() {
            return new UiTicks(onUiThread::get, async::add, timer::add);
        }

        /** replays the queued async hops the way the display would */
        void runAsync() {
            final List<Runnable> pending = new ArrayList<>(async);
            async.clear();
            pending.forEach(Runnable::run);
        }

        /** replays the queued timer ticks the way the display would */
        void runTimer() {
            final List<Runnable> pending = new ArrayList<>(timer);
            timer.clear();
            pending.forEach(Runnable::run);
        }
    }

    @Test
    void tickFromTheUiThreadGoesStraightToTheTimer() {
        final Hops hops = new Hops();
        final UiTicks ticks = hops.create();
        final AtomicBoolean ran = new AtomicBoolean();

        ticks.tick(() -> ran.set(true));

        // no async hop was needed and the timer owns the tick
        assertEquals(0, hops.async.size());
        assertEquals(1, hops.timer.size());
        hops.runTimer();
        assertTrue(ran.get(), "the tick must run once the timer fires");
    }

    @Test
    void tickFromAWorkerThreadHopsOntoTheUiThreadFirst() {
        final Hops hops = new Hops();
        hops.onUiThread.set(false);
        final UiTicks ticks = hops.create();
        final AtomicBoolean ran = new AtomicBoolean();

        ticks.tick(() -> ran.set(true));

        // the widget call never reaches the timer from the worker thread:
        // the tick waits for the async hop, and the timer sees it only after
        assertEquals(1, hops.async.size());
        assertEquals(0, hops.timer.size());
        hops.runAsync();
        assertEquals(1, hops.timer.size());
        assertEquals(false, Boolean.valueOf(ran.get()));
        hops.runTimer();
        assertTrue(ran.get(), "the tick must run on the UI thread");
    }

    @Test
    void everyTickOfOneBatchIsDeliveredInOrder() {
        final Hops hops = new Hops();
        hops.onUiThread.set(false);
        final UiTicks ticks = hops.create();
        final List<String> order = new ArrayList<>();

        ticks.tick(() -> order.add("user"));
        ticks.tick(() -> order.add("busy"));
        hops.runAsync();
        hops.runTimer();

        assertEquals(List.of("user", "busy"), order);
    }
}
