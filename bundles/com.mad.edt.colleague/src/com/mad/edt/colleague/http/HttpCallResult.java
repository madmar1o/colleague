package com.mad.edt.colleague.http;

import java.io.Closeable;
import java.util.stream.Stream;

/**
 * The answer to one {@link HttpCallSpec}: status code plus either the whole
 * body text or a single stream of body lines. The line stream must be closed
 * by the consumer; closing releases the connection, so abandoning a stopped
 * call stops the transfer instead of draining it.
 */
public final class HttpCallResult implements Closeable {

    private final int status;
    private final String bodyText;
    private final Stream<String> bodyLines;

    private HttpCallResult(final int theStatus, final String theBodyText,
        final Stream<String> theBodyLines) {
        status = theStatus;
        bodyText = theBodyText;
        bodyLines = theBodyLines;
    }

    /**
     * @param theStatus HTTP status code
     * @param theBodyText whole body as text
     * @return a buffered answer
     */
    public static HttpCallResult ofBody(final int theStatus, final String theBodyText) {
        return new HttpCallResult(theStatus, theBodyText, null);
    }

    /**
     * @param theStatus HTTP status code
     * @param theBodyLines body lines, consumable once, closeable
     * @return a line-streaming answer
     */
    public static HttpCallResult ofLines(final int theStatus, final Stream<String> theBodyLines) {
        return new HttpCallResult(theStatus, null, theBodyLines);
    }

    /**
     * @return HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return whole body text, {@code null} for a line-streaming answer
     */
    public String getBodyText() {
        return bodyText;
    }

    /**
     * @return body lines for a streaming call, iterable once; finish with
     *         {@link #close()} to release the connection
     */
    public Stream<String> lines() {
        return bodyLines;
    }

    /**
     * Releases the underlying connection of a streaming answer. Closing twice
     * or closing a buffered answer does nothing.
     */
    @Override
    public void close() {
        if(bodyLines != null) {
            bodyLines.close();
        }
    }
}
