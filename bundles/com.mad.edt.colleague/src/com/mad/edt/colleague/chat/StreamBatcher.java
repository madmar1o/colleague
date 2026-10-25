package com.mad.edt.colleague.chat;

import java.util.function.LongSupplier;

/**
 * Groups token-level answer chunks into batches so that a streaming turn
 * pushes a handful of events per second to the chat page instead of one per
 * token. A batch goes out once the accumulated text reaches the size cap or
 * the pause since the previous push reaches the interval; the very first
 * chunk always goes out immediately because the timer starts at zero.
 * This is the batching the streaming UI had inline in its panel, kept free
 * of UI and bridge types so it can be tested with a clock under control.
 */
public final class StreamBatcher {

    private final int myMaxBatchChars;
    private final long myMinIntervalMs;
    private final LongSupplier myClock;

    private final StringBuilder myBuffer = new StringBuilder();
    private long myLastFlushAtMs;

    /**
     * Same policy with the wall clock: batches of at most
     * {@code theMaxBatchChars} characters, at most one every
     * {@code theMinIntervalMs} milliseconds.
     */
    public StreamBatcher(final int theMaxBatchChars, final long theMinIntervalMs) {
        this(theMaxBatchChars, theMinIntervalMs, System::currentTimeMillis);
    }

    public StreamBatcher(final int theMaxBatchChars, final long theMinIntervalMs,
        final LongSupplier theClock) {
        myMaxBatchChars = theMaxBatchChars;
        myMinIntervalMs = theMinIntervalMs;
        myClock = theClock;
    }

    /**
     * Adds one streamed chunk.
     *
     * @return the whole accumulated text when this chunk completed a batch,
     *         {@code null} when the chunk stays inside the pending batch
     */
    public synchronized String accept(final String theChunk) {
        myBuffer.append(theChunk);
        final long now = myClock.getAsLong();
        final boolean flush =
            myBuffer.length() >= myMaxBatchChars || now - myLastFlushAtMs >= myMinIntervalMs;
        if(!flush) {
            return null;
        }
        myLastFlushAtMs = now;
        if(myBuffer.length() == 0) {
            return null;
        }
        final String batch = myBuffer.toString();
        myBuffer.setLength(0);
        return batch;
    }
}
