package com.mad.edt.colleague.agent;

/**
 * Read-only trace of one {@link AgentLoop} run: which round-trips happened
 * and which tools they decided, with timings. An outside verifier - the
 * console log, an unattended end-to-end harness - asserts on behaviour only
 * through this contract, so the loop stays free of any reporting concerns.
 */
public interface AgentObserver {

    /** Observer that listens to nothing, used when nobody asked for a trace. */
    AgentObserver NOOP = new AgentObserver() {
    };

    /**
     * A model round-trip finished.
     *
     * @param theStep 1-based round-trip number
     * @param theRequestedToolCalls how many tool calls the model asked for,
     *            {@code 0} when it answered instead
     */
    default void onRound(final int theStep, final int theRequestedToolCalls) {
        // listening is optional
    }

    /**
     * One requested tool call was executed.
     *
     * @param theStep round-trip number that requested the call
     * @param theName tool name as advertised to the model
     * @param theArgumentsJson arguments the model emitted
     * @param theResult full textual result fed back to the model
     * @param theElapsedMs wall-clock execution time in milliseconds
     */
    default void onToolCall(final int theStep, final String theName, final String theArgumentsJson,
        final String theResult, final long theElapsedMs) {
        // listening is optional
    }
}
