package com.mad.edt.colleague.http;

/**
 * A call that never became an HTTP answer: the endpoint could not be reached
 * or the waiting thread was interrupted. Callers translate it into the failure
 * vocabulary of their own domain.
 */
public class TransportFailure extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param theMessage what went wrong
     */
    public TransportFailure(final String theMessage) {
        super(theMessage);
    }

    /**
     * @param theMessage what went wrong
     * @param theCause underlying failure, may be {@code null}
     */
    public TransportFailure(final String theMessage, final Throwable theCause) {
        super(theMessage, theCause);
    }
}
