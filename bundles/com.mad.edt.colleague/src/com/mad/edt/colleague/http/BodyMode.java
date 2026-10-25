package com.mad.edt.colleague.http;

/**
 * How the caller wants the answer handed over: buffered as one body string,
 * or delivered as a stream of body lines as they arrive.
 */
public enum BodyMode {

    /** Read the whole body before returning it. */
    BUFFERED,

    /** Hand over the body line by line, closing the connection afterwards. */
    LINES
}
