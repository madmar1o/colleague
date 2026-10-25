package com.mad.edt.colleague.chat;

import com.mad.edt.colleague.llm.CancellationFlag;

/**
 * Bookkeeping of one running question at a time: which turn is current,
 * which cancellation latch and worker thread belong to it, and what the
 * stop request does to them. The UI keeps owning its scheduler and widgets;
 * this class only makes the stop path one object instead of several loose
 * fields, so a stale turn can never disturb the running one.
 */
public final class TurnController {

    /** the cancellation handle and worker thread of one started question */
    public static final class Turn {

        private final CancellationFlag myCancellation = new CancellationFlag();
        private volatile Thread myWorkerThread;

        /** the latch providers poll between streamed frames */
        public CancellationFlag cancellation() {
            return myCancellation;
        }

        void attachWorker(final Thread theWorker) {
            myWorkerThread = theWorker;
        }

        void interruptWorker() {
            final Thread worker = myWorkerThread;
            if(worker != null) {
                // interrupting the worker also cuts the wait for response
                // headers, which is the slowest phase when the endpoint
                // loads the model before answering
                worker.interrupt();
            }
        }
    }

    private Turn myCurrentTurn;
    private volatile boolean myAborted;
    private volatile long myAbortAtMs;

    /** Begins a question: the previous turn becomes stale from now on. */
    public synchronized Turn startTurn() {
        myAborted = false;
        myAbortAtMs = 0L;
        final Turn turn = new Turn();
        myCurrentTurn = turn;
        return turn;
    }

    /** Binds the thread running this turn so a stop can interrupt it. */
    public synchronized void attachWorker(final Turn theTurn, final Thread theWorker) {
        if(myCurrentTurn == theTurn) {
            theTurn.attachWorker(theWorker);
        }
    }

    /**
     * Requests the stop of the running turn: the providers notice the latch
     * between streamed frames and the worker is interrupted out of blocking
     * waits.
     *
     * @return {@code false} when there is no running turn to stop
     */
    public synchronized boolean abortTurn() {
        final Turn turn = myCurrentTurn;
        if(turn == null) {
            return false;
        }
        myAborted = true;
        myAbortAtMs = System.currentTimeMillis();
        turn.cancellation().cancel();
        turn.interruptWorker();
        return true;
    }

    /** @return whether the current turn was stopped by the user */
    public boolean isAborted() {
        return myAborted;
    }

    /** @return whether this turn is still the running one */
    public synchronized boolean isCurrent(final Turn theTurn) {
        return myCurrentTurn == theTurn;
    }

    /** Time since the stop request, for the stop-to-unwind latency log. */
    public long stopLatencyMs() {
        return System.currentTimeMillis() - myAbortAtMs;
    }
}
