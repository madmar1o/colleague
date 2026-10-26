package com.mad.edt.colleague.llm;

/**
 * Signals that a call to a language model provider failed: transport error,
 * non-success HTTP status or unexpected response body.
 */
public class LLMException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    /**
     * @param theMessage human readable description
     */
    public LLMException(final String theMessage) {
        this(theMessage, -1, null);
    }

    /**
     * @param theMessage human readable description
     * @param theCause underlying failure
     */
    public LLMException(final String theMessage, final Throwable theCause) {
        this(theMessage, -1, theCause);
    }

    /**
     * @param theMessage human readable description
     * @param theStatusCode HTTP status code, {@code -1} when not applicable
     * @param theCause underlying failure, may be {@code null}
     */
    public LLMException(final String theMessage, final int theStatusCode, final Throwable theCause) {
        super(theMessage, theCause);
        statusCode = theStatusCode;
    }

    /**
     * @return HTTP status code of the failed response, {@code -1} when the call
     *         did not produce an HTTP response
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Whether re-sending the very same request may still succeed. A server-side
     * failure ({@code 5xx}) or a call the network never delivered is repairable:
     * a local model server routinely keeps its serving port alive while only
     * its model process dies, and loads a fresh one on the next request. A
     * rejected request ({@code 4xx}) or a bad response body is permanent for
     * this payload and re-sending it only burns the turn.
     *
     * @return {@code true} when one repair attempt is worth its cost
     */
    public boolean isTransient() {
        return statusCode >= 500 || getCause() instanceof com.mad.edt.colleague.http.TransportFailure;
    }
}
