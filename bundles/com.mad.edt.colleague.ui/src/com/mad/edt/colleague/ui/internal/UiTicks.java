package com.mad.edt.colleague.ui.internal;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Delivers a runnable to the widget thread regardless of the thread that
 * produced it. The bridge push path is called from the UI thread (page
 * commands) and from background jobs alike (the answer job reports its status
 * line, the stop path lands its partial turn), while running page script is
 * only legal on the UI thread: a foreign-thread tick would throw
 * {@code Invalid thread access} and silently lose the event before the page
 * ever sees it.
 *
 * <p>The dispatcher keeps the two hops apart on purpose: a tick produced on
 * the UI thread needs only the timer step (a render-tick delay), while a tick
 * from a worker first hops onto the UI thread and only then asks the timer.
 * The test pins both orders with a fake display.</p>
 */
final class UiTicks {

    private final BooleanSupplier ourOnUiThread;
    private final Consumer<Runnable> ourAsyncHop;
    private final Consumer<Runnable> ourTimer;

    /**
     * @param theOnUiThread tells whether the calling thread is the widget
     *            thread
     * @param theAsyncHop queues a runnable on the widget thread
     * @param theTimer queues a runnable for the next widget tick
     */
    UiTicks(final BooleanSupplier theOnUiThread, final Consumer<Runnable> theAsyncHop,
        final Consumer<Runnable> theTimer) {
        ourOnUiThread = theOnUiThread;
        ourAsyncHop = theAsyncHop;
        ourTimer = theTimer;
    }

    /**
     * Runs the task on the next widget tick, hopping onto the widget thread
     * first when the caller is a worker.
     *
     * @param theTask work that touches the page or its widgets
     */
    void tick(final Runnable theTask) {
        if(ourOnUiThread.getAsBoolean()) {
            ourTimer.accept(theTask);
        } else {
            ourAsyncHop.accept(() -> ourTimer.accept(theTask));
        }
    }
}
