package com.mad.edt.colleague.llm;

/**
 * Unchecked signal thrown by streaming loops when the shared
 * {@link CancellationFlag} is set. It deliberately bypasses the "ignore this
 * malformed frame" error handling of the stream readers, unwinds the read
 * loop and closes the underlying connection. Providers translate it into a
 * partial {@link ChatResult} rather than an error.
 */
public final class TurnCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Standard exception for the shared transport package.
     */
    public TurnCancelledException() {
        super("generation turn cancelled");
    }
}
