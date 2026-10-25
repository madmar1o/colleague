package com.mad.edt.colleague.http;

/**
 * The single place where HTTP calls leave the plugin. Business clients describe
 * a call as an {@link HttpCallSpec} and receive an {@link HttpCallResult}; the
 * seam lets tests replace the network with an in-memory double while the
 * production adapter stays a detail of one class.
 */
public interface HttpTransport {

    /**
     * Performs one call.
     *
     * @param theCall what to send and how the answer should arrive
     * @return status plus body or body lines; a line-streaming result must be
     *         closed by the consumer
     * @throws TransportFailure when the endpoint is unreachable or the call
     *         was interrupted; the thread interrupt flag is restored
     */
    HttpCallResult send(HttpCallSpec theCall) throws TransportFailure;

    /**
     * @return the production adapter backed by the JDK HTTP client
     */
    static HttpTransport createDefault() {
        return new JavaHttpTransport(5_000);
    }
}
