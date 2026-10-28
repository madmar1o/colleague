package com.mad.edt.colleague.ui.internal;

import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

/**
 * Startup budget of the Colleague view: everything heavy must move off the
 * path that paints the tab for the first time. The chat page browser is
 * queued behind a paint tick so the tab switches before the browser widget
 * initializes, and the whole-project index job waits for a grace period as a
 * system job so the user's own jobs keep the scheduler slots. The view wires
 * exactly this seam, which lets the tests pin the budget without a live
 * workbench.
 */
public final class StartupDeferral {

    /**
     * Grace period between opening the view and the whole-project index:
     * reindexing competes with the user's builds for CPU, so it may only
     * start once the session had time to settle.
     */
    public static final long INDEX_JOB_DELAY_MS = 30_000L;

    /** Hook that runs a task once the UI painted the freshly opened page. */
    public interface Painter {

        /**
         * Queues the task to run after the current paint pass.
         *
         * @param theTask heavy build to run off the paint path
         */
        void afterFirstPaint(Runnable theTask);
    }

    private final Painter ourPainter;

    public StartupDeferral(final Painter thePainter) {
        ourPainter = thePainter;
    }

    /**
     * @return a deferral backed by the live display's event queue
     */
    public static StartupDeferral forDisplay() {
        return new StartupDeferral(task -> Display.getDefault().asyncExec(task));
    }

    /**
     * Queues one heavy UI build (a browser widget, a page of controls) so it
     * runs after the freshly opened tab had its first paint.
     *
     * @param theBuildTask the build to defer
     */
    public void defer(final Runnable theBuildTask) {
        ourPainter.afterFirstPaint(theBuildTask);
    }

    /**
     * Creates the whole-project index job and arms it with the grace period:
     * the job enters the scheduler in the waiting state and starts only once
     * {@link #INDEX_JOB_DELAY_MS} elapsed. It is a system job, so a user
     * action can still crowd it out of the scheduler.
     *
     * @param theBody index work, run on a job thread after the delay
     * @return the scheduled job, still waiting when this method returns
     */
    public static Job scheduleIndexJob(final IJobFunction theBody) {
        final Job index = Job.create("Colleague: project index", theBody);
        index.setSystem(true);
        index.schedule(INDEX_JOB_DELAY_MS);
        return index;
    }
}
