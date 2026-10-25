package com.mad.edt.colleague.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Base class with shared {@code java.net.http} plumbing: JSON POST/GET with
 * timeouts, authentication header and error wrapping into {@link LLMException}.
 */
abstract class AbstractHttpLLMProvider implements ILLMProvider {

    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final ProviderConfig config;
    private final HttpClient client;

    /**
     * @param theConfig endpoint configuration
     */
    protected AbstractHttpLLMProvider(final ProviderConfig theConfig) {
        config = theConfig;
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(theConfig.getConnectTimeoutMs()))
            .build();
    }

    @Override
    public String getId() {
        return config.getId();
    }

    @Override
    public ProviderConfig getConfig() {
        return config;
    }

    /**
     * POSTs a JSON body and returns the parsed JSON response.
     *
     * @param thePath path appended to the base URL, starting with a slash
     * @param theBody request JSON
     * @param theTimeoutMs read timeout for this particular call
     * @return parsed response body
     * @throws LLMException on transport failure or non-200 status
     */
    protected JsonObject postJson(final String thePath, final JsonObject theBody, final int theTimeoutMs)
        throws LLMException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.getBaseUrl() + thePath))
            .timeout(Duration.ofMillis(theTimeoutMs))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(theBody.toString(), StandardCharsets.UTF_8));
        return send(request);
    }

    /**
     * GETs a JSON document from the endpoint.
     *
     * @param thePath path appended to the base URL, starting with a slash
     * @param theTimeoutMs read timeout for this particular call
     * @return parsed response body
     * @throws LLMException on transport failure or non-200 status
     */
    protected JsonObject getJson(final String thePath, final int theTimeoutMs) throws LLMException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.getBaseUrl() + thePath))
            .timeout(Duration.ofMillis(theTimeoutMs))
            .header("Accept", "application/json")
            .GET();
        return send(request);
    }

    /**
     * POSTs a JSON body and consumes the response as a stream of JSON
     * events, one per line. Blank lines and unparseable lines are skipped;
     * SSE {@code data:} prefixes are stripped so both line-delimited JSON
     * (local runtimes) and event streams (gateway-style endpoints) work.
     *
     * @param thePath path appended to the base URL, starting with a slash
     * @param theBody request JSON
     * @param theTimeoutMs timeout until response headers arrive
     * @param theOnEvent called for every parsed event object
     * @throws LLMException on transport failure or non-2xx status
     */
    protected void postJsonStream(final String thePath, final JsonObject theBody,
        final int theTimeoutMs, final Consumer<JsonObject> theOnEvent) throws LLMException {
        postJsonStream(thePath, theBody, theTimeoutMs, new CancellationFlag(), theOnEvent);
    }

    /**
     * Same call with the cancellation latch of the owning turn: every streamed
     * frame re-checks the flag, and when it is set the read loop unwinds
     * through {@link TurnCancelledException}, closing the connection.
     *
     * @param thePath endpoint path
     * @param theBody request body
     * @param theTimeoutMs timeout until response headers arrive
     * @param theCancellation turn cancellation latch
     * @param theOnEvent called for every parsed event object
     * @throws LLMException on transport failure or non-2xx status
     */
    protected void postJsonStream(final String thePath, final JsonObject theBody,
        final int theTimeoutMs, final CancellationFlag theCancellation,
        final Consumer<JsonObject> theOnEvent) throws LLMException {
        final HttpRequest.Builder request = HttpRequest
            .newBuilder(URI.create(config.getBaseUrl() + thePath))
            .timeout(Duration.ofMillis(theTimeoutMs))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(theBody.toString(), StandardCharsets.UTF_8));
        if(config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            request.header("Authorization", "Bearer " + config.getApiKey());
        }
        final HttpResponse<java.util.stream.Stream<String>> response;
        try {
            final long startedAt = System.currentTimeMillis();
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
            System.err.println("[colleague] stream headers after "
                + (System.currentTimeMillis() - startedAt) + " ms from "
                + config.getBaseUrl() + thePath);
        } catch (final IOException ex) {
            throw new LLMException("Cannot reach endpoint " + config.getBaseUrl()
                + ": " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LLMException("Call to " + config.getBaseUrl() + " interrupted", ex);
        }
        if(theCancellation.isCancelled()) {
            // the wait for headers is the slowest phase (endpoints may load
            // the model first): observing the latch here closes the stream
            // right after headers arrive instead of waiting for any frame
            response.body().close();
            throw new TurnCancelledException();
        }
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            final StringBuilder excerpt = new StringBuilder();
            response.body().limit(4).forEach(line -> excerpt.append(line));
            throw new LLMException("Endpoint " + config.getBaseUrl() + " returned HTTP "
                + response.statusCode() + ": " + excerpt, response.statusCode(), null);
        }
        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if(theCancellation.isCancelled()) {
                    throw new TurnCancelledException();
                }
                final String trimmed = line.strip();
                if(trimmed.isEmpty()) {
                    return;
                }
                String payload = trimmed;
                if(payload.startsWith("data:")) {
                    payload = payload.substring(5).strip();
                }
                if(payload.isEmpty() || "[DONE]".equals(payload)) {
                    return;
                }
                try {
                    theOnEvent.accept(JsonParser.parseString(payload).getAsJsonObject());
                } catch (final TurnCancelledException ex) {
                    // a consumer that observed cancellation: unwinds the loop
                    throw ex;
                } catch (final RuntimeException ex) {
                    // unparsable stream frame, ignore it and keep reading
                }
            });
        }
    }

    /**
     * Resolves the model for a request: the requested one or the configured default.
     *
     * @param theModel requested model name, may be {@code null}
     * @return model to use
     * @throws LLMException when neither the request nor the config names a model
     */
    protected String resolveModel(final String theModel) throws LLMException {
        if(theModel != null && !theModel.isEmpty()) {
            return theModel;
        }
        if(config.getDefaultModel() != null && !config.getDefaultModel().isEmpty()) {
            return config.getDefaultModel();
        }
        throw new LLMException("No model selected for provider '" + config.getId() + "'");
    }

    /**
     * Reads a string member of a JSON object.
     *
     * @param theObject the object
     * @param theName member name
     * @return value or {@code null} when absent or not a string
     */
    protected static String textOrNull(final JsonObject theObject, final String theName) {
        final JsonElement element = theObject.get(theName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /**
     * Reads an integer member of a JSON object.
     *
     * @param theObject the object
     * @param theName member name
     * @return value or {@code 0} when absent
     */
    protected static int intOrZero(final JsonObject theObject, final String theName) {
        final JsonElement element = theObject.get(theName);
        if(element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return element.getAsInt();
    }

    private JsonObject send(final HttpRequest.Builder theRequestBuilder) throws LLMException {
        if(config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            theRequestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }
        final HttpResponse<String> response;
        try {
            response = client.send(theRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final IOException ex) {
            throw new LLMException("Cannot reach endpoint " + config.getBaseUrl() + ": " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LLMException("Call to " + config.getBaseUrl() + " interrupted", ex);
        }
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LLMException("Endpoint " + config.getBaseUrl() + " returned HTTP "
                + response.statusCode() + ": " + excerpt(response.body()), response.statusCode(), null);
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (final RuntimeException ex) {
            throw new LLMException("Unexpected non-JSON response from " + config.getBaseUrl()
                + ": " + excerpt(response.body()), ex);
        }
    }

    /**
     * Parses a JSON object string; falls back to an empty object when the
     * text is not valid JSON.
     *
     * @param theJson JSON text, may be {@code null}
     * @return parsed object or an empty one
     */
    protected static JsonObject parseArguments(final String theJson) {
        try {
            return JsonParser.parseString(theJson).getAsJsonObject();
        } catch (final RuntimeException ex) {
            return new JsonObject();
        }
    }

    private static String excerpt(final String theBody) {
        if(theBody == null) {
            return "";
        }
        return theBody.length() <= MAX_ERROR_BODY_CHARS ? theBody : theBody.substring(0, MAX_ERROR_BODY_CHARS);
    }
}
