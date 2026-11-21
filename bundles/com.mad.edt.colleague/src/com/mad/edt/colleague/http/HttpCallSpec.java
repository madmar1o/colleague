package com.mad.edt.colleague.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One JSON call described without any transport technology: absolute URL,
 * HTTP method, optional JSON body as text and the desired answer form.
 * Instances are immutable.
 */
public final class HttpCallSpec {

    /** Media type every JSON transport call negotiates. */
    private static final String JSON_MEDIA_TYPE = "application/json";

    private final String url;
    private final String method;
    private final String jsonBody;
    private final Map<String, String> headers;
    private final int timeoutMs;
    private final BodyMode mode;

    private HttpCallSpec(final String theUrl, final String theMethod, final String theJsonBody,
        final Map<String, String> theHeaders, final int theTimeoutMs, final BodyMode theMode) {
        url = theUrl;
        method = theMethod;
        jsonBody = theJsonBody;
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(theHeaders));
        timeoutMs = theTimeoutMs;
        mode = theMode;
    }

    /**
     * @param theUrl absolute endpoint URL
     * @param theTimeoutMs budget for this call in milliseconds
     * @return a GET call accepting JSON
     */
    public static HttpCallSpec getJson(final String theUrl, final int theTimeoutMs) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", JSON_MEDIA_TYPE);
        return new HttpCallSpec(theUrl, "GET", null, headers, theTimeoutMs, BodyMode.BUFFERED);
    }

    /**
     * @param theUrl absolute endpoint URL
     * @param theJsonBody request body serialized to JSON text
     * @param theTimeoutMs budget for this call in milliseconds
     * @return a POST call sending and accepting JSON
     */
    public static HttpCallSpec postJson(final String theUrl, final String theJsonBody,
        final int theTimeoutMs) {
        return json("POST", theUrl, theJsonBody, theTimeoutMs, BodyMode.BUFFERED);
    }

    /**
     * @param theUrl absolute endpoint URL
     * @param theJsonBody request body serialized to JSON text
     * @param theTimeoutMs budget for this call in milliseconds
     * @return a POST call whose answer arrives as a stream of body lines
     */
    public static HttpCallSpec postJsonStream(final String theUrl, final String theJsonBody,
        final int theTimeoutMs) {
        return json("POST", theUrl, theJsonBody, theTimeoutMs, BodyMode.LINES);
    }

    /**
     * @param theMethod HTTP method, e.g. {@code PUT} or {@code DELETE}
     * @param theUrl absolute endpoint URL
     * @param theJsonBody request body as JSON text, {@code null} for no body
     * @param theTimeoutMs budget for this call in milliseconds
     * @return a call in that method sending JSON when it has a body
     */
    public static HttpCallSpec json(final String theMethod, final String theUrl,
        final String theJsonBody, final int theTimeoutMs) {
        return json(theMethod, theUrl, theJsonBody, theTimeoutMs, BodyMode.BUFFERED);
    }

    private static HttpCallSpec json(final String theMethod, final String theUrl,
        final String theJsonBody, final int theTimeoutMs, final BodyMode theMode) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", JSON_MEDIA_TYPE);
        headers.put("Accept", JSON_MEDIA_TYPE);
        return new HttpCallSpec(theUrl, theMethod, theJsonBody, headers, theTimeoutMs, theMode);
    }

    /**
     * @return a copy carrying this additional header
     * @param theName header name
     * @param theValue header value
     */
    public HttpCallSpec withHeader(final String theName, final String theValue) {
        final Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(theName, theValue);
        return new HttpCallSpec(url, method, jsonBody, copy, timeoutMs, mode);
    }

    /**
     * @return absolute endpoint URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * @return HTTP method
     */
    public String getMethod() {
        return method;
    }

    /**
     * @return request body as JSON text or {@code null} for no body
     */
    public String getJsonBody() {
        return jsonBody;
    }

    /**
     * @return request headers, insertion-ordered and unmodifiable
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * @return call budget in milliseconds
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * @return how the answer should be handed over
     */
    public BodyMode getMode() {
        return mode;
    }
}
