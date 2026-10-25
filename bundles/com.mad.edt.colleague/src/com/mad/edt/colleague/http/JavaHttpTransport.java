package com.mad.edt.colleague.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Production adapter: performs calls with the JDK HTTP client. A streaming
 * answer hands over the body as a line stream bound to the connection, so
 * closing it (the usual finish of a stopped turn) terminates the transfer.
 */
public final class JavaHttpTransport implements HttpTransport {

    private final HttpClient client;

    /**
     * @param theConnectTimeoutMs budget for establishing a connection
     */
    public JavaHttpTransport(final int theConnectTimeoutMs) {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(theConnectTimeoutMs))
            .build();
    }

    @Override
    public HttpCallResult send(final HttpCallSpec theCall) throws TransportFailure {
        final HttpRequest.Builder request =
            HttpRequest.newBuilder(URI.create(theCall.getUrl()))
                .timeout(Duration.ofMillis(theCall.getTimeoutMs()));
        for(final Map.Entry<String, String> header : theCall.getHeaders().entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        final String method = theCall.getMethod();
        if("GET".equals(method)) {
            request.GET();
        } else if(theCall.getJsonBody() == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers
                .ofString(theCall.getJsonBody(), StandardCharsets.UTF_8));
        }
        try {
            if(theCall.getMode() == BodyMode.LINES) {
                final HttpResponse<java.util.stream.Stream<String>> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
                return HttpCallResult.ofLines(response.statusCode(), response.body());
            }
            final HttpResponse<String> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            return HttpCallResult.ofBody(response.statusCode(), response.body());
        } catch (final IOException ex) {
            throw new TransportFailure("cannot reach " + theCall.getUrl()
                + ": " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TransportFailure("call to " + theCall.getUrl() + " interrupted", ex);
        }
    }
}
