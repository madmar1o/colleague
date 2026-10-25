package com.mad.edt.colleague.context.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal vector store client speaking the plain REST/JSON contract of the
 * vector database: collection lifecycle, idempotent point upserts, filtered
 * deletes and similarity search. Only {@code java.net.http} and the JSON
 * library already used by the plugin are involved. Instances are thread safe.
 */
public final class QdrantClient {

    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final String baseUrl;
    private final HttpClient client;
    private final int readTimeoutMs;

    /**
     * @param theBaseUrl server root, e.g. {@code http://127.0.0.1:6333}
     * @param theReadTimeoutMs timeout for one request
     */
    public QdrantClient(final String theBaseUrl, final int theReadTimeoutMs) {
        baseUrl = theBaseUrl.endsWith("/")
            ? theBaseUrl.substring(0, theBaseUrl.length() - 1) : theBaseUrl;
        readTimeoutMs = theReadTimeoutMs;
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    /**
     * Deterministic point key for one chunk of one file, so re-upserting the
     * same chunk overwrites it and no bookkeeping outside the store is needed.
     *
     * @param thePath workspace-relative file path
     * @param theChunkIndex zero-based chunk number
     * @return UUID-formatted id accepted by the store
     */
    public static String pointId(final String thePath, final int theChunkIndex) {
        return UUID.nameUUIDFromBytes(
            (thePath + '#' + theChunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * @return {@code true} when the server answers to {@code /collections}
     */
    public boolean isAlive() {
        try {
            final JsonObject response = call("GET", "/collections", null);
            return response != null && response.has("result");
        } catch (final QdrantException ex) {
            return false;
        }
    }

    /**
     * @param theCollection collection name
     * @return whether it exists on the server
     */
    public boolean collectionExists(final String theCollection) {
        try {
            call("GET", "/collections/" + theCollection, null);
            return true;
        } catch (final QdrantException ex) {
            return false;
        }
    }

    /**
     * Creates a cosine-similarity collection when it is absent, and verifies
     * afterwards that the stored configuration really has the requested
     * vector size: the server silently ignores misspelled creation bodies
     * and would otherwise leave a vectorless collection behind.
     *
     * @param theCollection collection name
     * @param theVectorSize vector dimensionality
     * @throws QdrantException when creation fails or the stored configuration
     *             does not match the requested dimensionality
     */
    public void ensureCollection(final String theCollection, final int theVectorSize)
        throws QdrantException {
        final JsonObject existing =
            call("GET", "/collections/" + theCollection, null);
        if(vectorConfigOf(existing) == null) {
            final JsonObject vectorParams = new JsonObject();
            vectorParams.addProperty("size", Integer.valueOf(theVectorSize));
            vectorParams.addProperty("distance", "Cosine");
            final JsonObject body = new JsonObject();
            // the create-collection contract takes vector settings at the
            // top level under the plural "vectors" key; other spellings are
            // silently ignored by the server and leave a vectorless store
            body.add("vectors", vectorParams);
            call("PUT", "/collections/" + theCollection, body);
        }
        final JsonObject stored = vectorConfigOf(
            call("GET", "/collections/" + theCollection, null));
        if(stored == null) {
            throw new QdrantException("vector store created collection '" + theCollection
                + "' without vector configuration");
        }
        final int storedSize = stored.has("size") ? stored.get("size").getAsInt() : -1;
        if(storedSize != theVectorSize) {
            throw new QdrantException("collection '" + theCollection + "' holds vectors of size "
                + storedSize + ", embedding model needs " + theVectorSize);
        }
    }

    /**
     * Vector settings from a collection description: either the unnamed
     * single-vector form or any entry of the named-vectors map.
     */
    private static JsonObject vectorConfigOf(final JsonObject theDescription) {
        if(theDescription == null || !theDescription.has("result")) {
            return null;
        }
        final JsonObject result = theDescription.getAsJsonObject("result");
        final JsonObject config = result.has("config")
            ? result.getAsJsonObject("config") : null;
        final JsonObject params = config != null && config.has("params")
            ? config.getAsJsonObject("params") : null;
        if(params == null || !params.has("vectors") || params.get("vectors").isJsonNull()) {
            return null;
        }
        final com.google.gson.JsonElement vectors = params.get("vectors");
        if(!vectors.isJsonObject()) {
            return null;
        }
        final JsonObject object = vectors.getAsJsonObject();
        if(object.has("size")) {
            return object;
        }
        for(final Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
            if(entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject()
                .has("size")) {
                return entry.getValue().getAsJsonObject();
            }
        }
        return null;
    }

    /**
     * Drops a collection, e.g. before rebuilding it with another model.
     *
     * @param theCollection collection name
     * @throws QdrantException when deletion fails
     */
    public void dropCollection(final String theCollection) throws QdrantException {
        call("DELETE", "/collections/" + theCollection, null);
    }

    /**
     * One stored chunk with its vectors and metadata payload.
     */
    public static final class Point {

        private final String id;
        private final float[] vector;
        private final JsonObject payload;

        /**
         * @param theId point key, see {@link #pointId}
         * @param theVector embedding vector
         * @param thePayload metadata: file path, lines, mtime, hash, text
         */
        public Point(final String theId, final float[] theVector, final JsonObject thePayload) {
            id = theId;
            vector = theVector;
            payload = thePayload;
        }
    }

    /**
     * Inserts or overwrites a batch of points.
     *
     * @param theCollection collection name
     * @param thePoints points to write
     * @throws QdrantException when the write fails
     */
    public void upsert(final String theCollection, final List<Point> thePoints)
        throws QdrantException {
        final JsonArray points = new JsonArray();
        for(final Point point : thePoints) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("id", point.id);
            entry.add("vector", toJsonVector(point.vector));
            entry.add("payload", point.payload);
            points.add(entry);
        }
        final JsonObject body = new JsonObject();
        body.add("points", points);
        call("PUT", "/collections/" + theCollection + "/points?wait=true", body);
    }

    /**
     * Deletes every point whose payload carries the given file path.
     *
     * @param theCollection collection name
     * @param theField payload field to match
     * @param theValue exact field value
     * @throws QdrantException when the delete fails
     */
    public void deleteByField(final String theCollection, final String theField,
        final String theValue) throws QdrantException {
        final JsonObject match = new JsonObject();
        match.addProperty("key", theField);
        match.addProperty("value", theValue);
        final JsonObject condition = new JsonObject();
        condition.add("match", match);
        final JsonArray must = new JsonArray();
        must.add(condition);
        final JsonObject filter = new JsonObject();
        filter.add("must", must);
        final JsonObject body = new JsonObject();
        body.add("filter", filter);
        call("POST", "/collections/" + theCollection + "/points/delete?wait=true", body);
    }

    /**
     * One search hit: stored payload plus similarity score.
     */
    public static final class Hit {

        private final JsonObject payload;
        private final double score;

        Hit(final JsonObject thePayload, final double theScore) {
            payload = thePayload;
            score = theScore;
        }

        /**
         * @return stored chunk metadata
         */
        public JsonObject getPayload() {
            return payload;
        }

        /**
         * @return cosine similarity in {@code [0..1]} for normalized vectors
         */
        public double getScore() {
            return score;
        }
    }

    /**
     * Runs one similarity search.
     *
     * @param theCollection collection name
     * @param theVector query vector
     * @param theLimit maximum number of hits
     * @param theScoreThreshold drop hits scoring below it, {@code null} for none
     * @return hits ordered by decreasing score
     * @throws QdrantException when the search fails
     */
    public List<Hit> search(final String theCollection, final float[] theVector,
        final int theLimit, final Double theScoreThreshold) throws QdrantException {
        final JsonObject body = new JsonObject();
        body.add("vector", toJsonVector(theVector));
        body.addProperty("limit", Integer.valueOf(theLimit));
        body.addProperty("with_payload", Boolean.TRUE);
        if(theScoreThreshold != null) {
            body.addProperty("score_threshold", theScoreThreshold);
        }
        final JsonObject result =
            call("POST", "/collections/" + theCollection + "/points/search", body);
        final List<Hit> hits = new ArrayList<>();
        if(result != null && result.has("result")) {
            for(final com.google.gson.JsonElement element : result.getAsJsonArray("result")) {
                final JsonObject hit = element.getAsJsonObject();
                final JsonObject payload = hit.getAsJsonObject("payload");
                final double score = hit.has("score") ? hit.get("score").getAsDouble() : 0.0;
                hits.add(new Hit(payload == null ? new JsonObject() : payload,
                    Double.valueOf(score)));
            }
        }
        return hits;
    }

    /**
     * Number of stored points, for progress reporting and health checks.
     *
     * @param theCollection collection name
     * @return point count, {@code -1} when unknown
     */
    public long count(final String theCollection) {
        try {
            final JsonObject body = new JsonObject();
            body.addProperty("exact", Boolean.TRUE);
            final JsonObject result =
                call("POST", "/collections/" + theCollection + "/points/count", body);
            if(result != null && result.has("result")
                && result.getAsJsonObject("result").has("count")) {
                return result.getAsJsonObject("result").get("count").getAsLong();
            }
        } catch (final QdrantException ex) {
            // count is informational only
        }
        return -1L;
    }

    private JsonObject call(final String theMethod, final String thePath, final JsonObject theBody)
        throws QdrantException {
        final HttpRequest.Builder request = HttpRequest
            .newBuilder(URI.create(baseUrl + thePath))
            .timeout(Duration.ofMillis(readTimeoutMs))
            .header("Content-Type", "application/json");
        if("GET".equals(theMethod)) {
            request.GET();
        } else if("DELETE".equals(theMethod)) {
            request.method("DELETE",
                theBody == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(theBody.toString(),
                        StandardCharsets.UTF_8));
        } else {
            request.method(theMethod, HttpRequest.BodyPublishers
                .ofString(theBody == null ? "{}" : theBody.toString(), StandardCharsets.UTF_8));
        }
        final HttpResponse<String> response;
        try {
            response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final IOException ex) {
            throw new QdrantException("cannot reach vector store " + baseUrl
                + ": " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QdrantException("vector store call interrupted", ex);
        }
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new QdrantException("vector store returned HTTP " + response.statusCode()
                + " for " + theMethod + ' ' + thePath + ": " + excerpt(response.body()));
        }
        try {
            final JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if(json.has("status")) {
                final com.google.gson.JsonElement status = json.get("status");
                if(status.isJsonObject() && status.getAsJsonObject().has("error")) {
                    throw new QdrantException("vector store error: " + excerpt(response.body()));
                }
            }
            return json;
        } catch (final RuntimeException ex) {
            throw new QdrantException("unexpected vector store response: "
                + excerpt(response.body()), ex);
        }
    }

    private static JsonArray toJsonVector(final float[] theVector) {
        final JsonArray array = new JsonArray();
        for(final float value : theVector) {
            array.add(Float.valueOf(value));
        }
        return array;
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
     * Failure of one vector store request.
     */
    public static final class QdrantException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param theMessage failure description
         */
        public QdrantException(final String theMessage) {
            super(theMessage);
        }

        /**
         * @param theMessage failure description
         * @param theCause transport or parse failure, may be {@code null}
         */
        public QdrantException(final String theMessage, final Throwable theCause) {
            super(theMessage, theCause);
        }
    }
}
