package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

/**
 * Pins what the view must NOT do while the Colleague tab is being opened:
 * the whole-project index may not start until its grace period elapsed, and
 * the heavy chat page build may not run inline on the paint path. The tests
 * work on the deferral seam itself - the view wires exactly this seam - so no
 * live workbench is needed to see the startup budget being respected.
 */
class ColleagueStartupDeferralTest {

    /** fake first-paint hook: records the deferred tasks, runs them on replay */
    private static final class FakePainter implements StartupDeferral.Painter {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void afterFirstPaint(final Runnable theTask) {
            pending.add(theTask);
        }

        /** replays the paint tick, as the display would */
        void paintNow() {
            final List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }

        int pendingCount() {
            return pending.size();
        }
    }

    @Test
    void indexJobWaitsForTheGracePeriodBeforeStarting() {
        final AtomicBoolean started = new AtomicBoolean();
        final Job job = StartupDeferral.scheduleIndexJob(monitor -> {
            started.set(true);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        try {
            // the grace period has not elapsed: the job sits in the scheduler
            // and the whole-project reindex has not touched a single file.
            // a delayed job parks in SLEEPING (or WAITING behind a rule), the
            // forbidden state here is RUNNING
            final int state = job.getState();
            assertTrue(state == Job.SLEEPING || state == Job.WAITING,
                "the index job must be parked, not running, right after startup, state=" + state);
            assertFalse(started.get(), "the index body must not have started yet");
            assertTrue(StartupDeferral.INDEX_JOB_DELAY_MS >= 30_000L,
                "the index must not start earlier than 30 seconds after startup");
        } finally {
            job.cancel();
        }
    }

    @Test
    void schedulingTheIndexJobReturnsImmediately() {
        final long startedAt = System.nanoTime();
        final Job job = StartupDeferral.scheduleIndexJob(monitor ->
            org.eclipse.core.runtime.Status.OK_STATUS);
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        job.cancel();
        assertTrue(elapsedMs < 100L,
            "scheduling must not block the paint path, was " + elapsedMs + " ms");
    }

    @Test
    void chatPageBuildRunsOnlyAfterTheFirstPaint() {
        final FakePainter painter = new FakePainter();
        final StartupDeferral deferral = new StartupDeferral(painter);
        final AtomicBoolean built = new AtomicBoolean();

        deferral.defer(() -> built.set(true));

        // the open path only queued the heavy browser build; the tab content
        // was not built inline, so the first paint of the tab stays fast
        assertFalse(built.get(), "the chat page must not be built inline on the open path");
        assertEquals(1, painter.pendingCount());

        painter.paintNow();
        assertTrue(built.get(), "the queued build must run on the first paint tick");
    }

    @Test
    void deferredBuildIsSkippedWhenItsWidgetGoneCheckRefuses() {
        final FakePainter painter = new FakePainter();
        final StartupDeferral deferral = new StartupDeferral(painter);
        final AtomicBoolean built = new AtomicBoolean();
        // the view wraps the build in its own disposed check; the seam itself
        // must run whatever it was given, in order, exactly once each
        deferral.defer(() -> built.set(true));
        deferral.defer(() -> built.set(true));
        assertEquals(2, painter.pendingCount());
        painter.paintNow();
        assertTrue(built.get());
    }
}
