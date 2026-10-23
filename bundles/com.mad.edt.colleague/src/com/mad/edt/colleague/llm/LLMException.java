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
}
