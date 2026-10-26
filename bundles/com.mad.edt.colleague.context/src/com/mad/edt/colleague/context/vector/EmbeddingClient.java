package com.mad.edt.colleague.context.vector;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;
import com.mad.edt.colleague.http.HttpTransport;
import com.mad.edt.colleague.http.TransportFailure;

/**
 * Text-to-vector client for the local embedding runtime. Talks to the native
 * {@code /api/embed} endpoint which accepts a batch of texts and returns one
 * vector per text in the same order. Every call goes through the
 * {@link HttpTransport} seam, so tests can pin the batch contract without a
 * runtime. Instances are thread safe.
 */
public final class EmbeddingClient {

    private static final int MAX_ERROR_BODY_CHARS = 300;

    /**
     * Residence lifetime requested with every batch: the embedding runtime
     * keeps the model loaded between hybrid searches and index batches, so
     * neither a user question nor a refresh pays the cold model load.
     */
    static final String RUNTIME_KEEP_ALIVE = "30m";

    private final String baseUrl;
    private final String model;
    private final HttpTransport transport;
    private final int readTimeoutMs;

    /**
     * @param theBaseUrl runtime root, e.g. {@code http://127.0.0.1:11435}
     * @param theModel embedding model name, e.g. {@code bge-m3}
     * @param theReadTimeoutMs timeout for one embedding batch
     */
    public EmbeddingClient(final String theBaseUrl, final String theModel,
        final int theReadTimeoutMs) {
        this(theBaseUrl, theModel, theReadTimeoutMs, HttpTransport.createDefault());
    }

    /**
     * @param theBaseUrl runtime root
     * @param theModel embedding model name
     * @param theReadTimeoutMs timeout for one embedding batch
     * @param theTransport transport seam used for every call
     */
    public EmbeddingClient(final String theBaseUrl, final String theModel,
        final int theReadTimeoutMs, final HttpTransport theTransport) {
        baseUrl = theBaseUrl.endsWith("/")
            ? theBaseUrl.substring(0, theBaseUrl.length() - 1) : theBaseUrl;
        model = theModel;
        readTimeoutMs = theReadTimeoutMs;
        transport = theTransport;
    }

    /**
     * @return configured embedding model name
     */
    public String getModel() {
        return model;
    }

    /**
     * Embeds a batch of texts in one request.
     *
     * @param theTexts texts to embed, non-empty
     * @return one vector per input text, same order
     * @throws EmbeddingException when the runtime is unreachable, rejects the
     *             request or returns an unexpected body
     */
    public List<float[]> embed(final List<String> theTexts) throws EmbeddingException {
        if(theTexts.isEmpty()) {
            throw new IllegalArgumentException("no texts to embed");
        }
        final JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("keep_alive", RUNTIME_KEEP_ALIVE);
        final JsonArray input = new JsonArray();
        for(final String text : theTexts) {
            input.add(text);
        }
        body.add("input", input);

        final String responseBody;
        try {
            final HttpCallResult response = transport.send(
                HttpCallSpec.postJson(baseUrl + "/api/embed", body.toString(), readTimeoutMs));
            if(response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new EmbeddingException("embedding runtime returned HTTP "
                    + response.getStatus() + ": " + excerpt(response.getBodyText()));
            }
            responseBody = response.getBodyText();
        } catch (final TransportFailure ex) {
            throw new EmbeddingException(ex.getMessage(), ex);
        }

        final JsonArray vectors;
        try {
            final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            vectors = json.getAsJsonArray("embeddings");
        } catch (final RuntimeException ex) {
            throw new EmbeddingException("unexpected embedding response: "
                + excerpt(responseBody), ex);
        }
        if(vectors == null || vectors.size() != theTexts.size()) {
            throw new EmbeddingException("embedding response has "
                + (vectors == null ? "no" : String.valueOf(vectors.size()))
                + " vectors for " + theTexts.size() + " texts");
        }
        final List<float[]> result = new ArrayList<>(vectors.size());
        for(int i = 0; i < vectors.size(); i++) {
            final JsonArray vector = vectors.get(i).getAsJsonArray();
            final float[] values = new float[vector.size()];
            for(int j = 0; j < vector.size(); j++) {
                values[j] = vector.get(j).getAsFloat();
            }
            result.add(values);
        }
        return result;
    }

    private static String excerpt(final String theBody) {
        if(theBody == null) {
            return "";
        }
        final String flat = theBody.replace('\n', ' ').strip();
        return flat.length() <= MAX_ERROR_BODY_CHARS
            ? flat : flat.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }

    /**
     * Failure of one embedding batch: transport problem, rejected request or
     * malformed response body.
     */
    public static final class EmbeddingException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param theMessage failure description
         */
        public EmbeddingException(final String theMessage) {
            super(theMessage);
        }

        /**
         * @param theMessage failure description
         * @param theCause transport failure, may be {@code null}
         */
        public EmbeddingException(final String theMessage, final Throwable theCause) {
            super(theMessage, theCause);
        }
    }
}
