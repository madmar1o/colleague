package com.mad.edt.colleague.llm;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts who currently occupies the local model generator. On a small GPU
 * only one model fits in memory at a time: while an interactive chat or
 * agent turn holds the generator, a background embedding pass hammering the
 * same server evicts the conversational model and every following step pays
 * to load it back. Interactive paths {@link #enter() enter} while they work;
 * background paths refuse to start a batch until
 * {@link #awaitIdle(long)} confirms the gate went quiet.
 */
public final class GeneratorGate {

    /**
     * Process-wide gate shared by every bundle: the generator in question is
     * one server, so ownership cannot be scoped to one plug-in's objects.
     */
    public static final GeneratorGate GLOBAL = new GeneratorGate();

    private final AtomicInteger holders = new AtomicInteger();

    /**
     * Takes one occupancy ticket. Every {@link #enter()} must be matched by a
     * {@link #leave()} in a finally block.
     */
    public void enter() {
        holders.incrementAndGet();
    }

    /**
     * Releases one ticket. More releases than entries are tolerated: the
     * counter floors at idle instead of opening a negative hole that would
     * keep background work waiting forever.
     */
    public void leave() {
        int current = holders.get();
        while(current > 0 && !holders.compareAndSet(current, current - 1)) {
            current = holders.get();
        }
    }

    /**
     * @return {@code true} when no holder occupies the generator right now
     */
    public boolean isIdle() {
        return holders.get() == 0;
    }

    /**
     * Waits for the generator to go quiet, at most the given time.
     *
     * @param theMaxWaitMs ceiling of the wait, {@code 0} only peeks
     * @return {@code true} when the gate is idle now; {@code false} when the
     *         wait timed out out or was interrupted - in the latter case the
     *         interrupt stays pending on the calling thread
     */
    public boolean awaitIdle(final long theMaxWaitMs) {
        final long deadline = System.currentTimeMillis() + Math.max(0L, theMaxWaitMs);
        while(!isIdle()) {
            final long remaining = deadline - System.currentTimeMillis();
            if(remaining <= 0L) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50L, remaining));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
