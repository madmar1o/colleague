package com.mad.edt.colleague.llm;

/**
 * Thread-safe cancellation latch shared by the UI and the worker thread that
 * runs one generation turn. The UI cancels it from the event thread when the
 * user presses stop; provider loops poll it between streamed frames and stop
 * reading from the transport, so generation ends within a frame or two
 * instead of running to completion.
 */
public final class CancellationFlag {

    private volatile boolean cancelled;

    /**
     * Signals that the owning turn is no longer wanted. Idempotent.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * @return {@code true} once {@link #cancel()} has been called
     */
    public boolean isCancelled() {
        return cancelled;
    }
}
