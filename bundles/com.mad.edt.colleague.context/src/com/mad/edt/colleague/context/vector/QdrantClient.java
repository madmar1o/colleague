package com.mad.edt.colleague.context.vector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;
import com.mad.edt.colleague.http.HttpTransport;
import com.mad.edt.colleague.http.TransportFailure;

/**
 * Minimal vector store client speaking the plain REST/JSON contract of the
 * vector database: collection lifecycle, idempotent point upserts, filtered
 * deletes and similarity search. Every call goes through the {@link HttpTransport}
 * seam, so tests can pin the request bodies without a server. Instances are
 * thread safe.
 */
public final class QdrantClient {

    private static final int MAX_ERROR_BODY_CHARS = 300;

    /** Points per page while walking the stored point set. */
    private static final int SCROLL_PAGE_LIMIT = 256;

    private final String baseUrl;
    private final HttpTransport transport;
    private final int readTimeoutMs;

    /**
     * @param theBaseUrl server root, e.g. {@code http://127.0.0.1:6333}
     * @param theReadTimeoutMs timeout for one request
     */
    public QdrantClient(final String theBaseUrl, final int theReadTimeoutMs) {
        this(theBaseUrl, theReadTimeoutMs, HttpTransport.createDefault());
    }

    /**
     * @param theBaseUrl server root
     * @param theReadTimeoutMs timeout for one request
     * @param theTransport transport seam used for every call
     */
    public QdrantClient(final String theBaseUrl, final int theReadTimeoutMs,
        final HttpTransport theTransport) {
        baseUrl = theBaseUrl.endsWith("/")
            ? theBaseUrl.substring(0, theBaseUrl.length() - 1) : theBaseUrl;
        readTimeoutMs = theReadTimeoutMs;
        transport = theTransport;
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
        // an absent collection answers the probe with an error, that is the
        // normal path into creation, not a failure
        if(!collectionExists(theCollection)) {
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
        // the store's filter condition carries the field key next to the
        // match object; nesting the key inside "match" is rejected there
        final JsonObject match = new JsonObject();
        match.addProperty("value", theValue);
        final JsonObject condition = new JsonObject();
        condition.addProperty("key", theField);
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
     * Walks every stored point of the collection in payload-only scroll
     * pages and collects the named payload fields of each point. The walk
     * hands the previous page offset to the next request and stops as soon
     * as the store stops handing offsets over.
     *
     * @param theCollection collection name
     * @param theFields payload fields to carry back
     * @return one payload object per stored point
     * @throws QdrantException when the store refuses a page
     */
    public List<JsonObject> scrollPayloads(final String theCollection,
        final List<String> theFields) throws QdrantException {
        final List<JsonObject> payloads = new ArrayList<>();
        String offset = null;
        while(true) {
            final JsonObject body = new JsonObject();
            body.addProperty("limit", Integer.valueOf(SCROLL_PAGE_LIMIT));
            body.addProperty("with_vector", Boolean.FALSE);
            final JsonArray fields = new JsonArray();
            for(final String field : theFields) {
                fields.add(field);
            }
            body.add("with_payload", fields);
            if(offset != null) {
                body.addProperty("offset", offset);
            }
            final JsonObject result =
                call("POST", "/collections/" + theCollection + "/points/scroll", body);
            final JsonObject page =
                result == null ? null : result.getAsJsonObject("result");
            if(page == null || !page.has("points")) {
                break;
            }
            int pagePoints = 0;
            for(final com.google.gson.JsonElement element : page.getAsJsonArray("points")) {
                final JsonObject payload = element.getAsJsonObject().getAsJsonObject("payload");
                payloads.add(payload == null ? new JsonObject() : payload);
                pagePoints++;
            }
            final com.google.gson.JsonElement next = page.get("next_page");
            if(next == null || next.isJsonNull() || pagePoints == 0) {
                break;
            }
            offset = next.getAsString();
        }
        return payloads;
    }

    /**
     * Number of stored points, for progress reporting and health checks.
     *
     * @param theCollection collection name
     * @return point count, {@code -1} when unknown
     */
    public long count(final String theCollection) {        try {
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
        HttpCallSpec call;
        if("GET".equals(theMethod)) {
            call = HttpCallSpec.getJson(baseUrl + thePath, readTimeoutMs);
        } else if("DELETE".equals(theMethod)) {
            call = HttpCallSpec.json("DELETE", baseUrl + thePath,
                theBody == null ? null : theBody.toString(), readTimeoutMs);
        } else {
            call = HttpCallSpec.json(theMethod, baseUrl + thePath,
                theBody == null ? "{}" : theBody.toString(), readTimeoutMs);
        }
        final HttpCallResult response;
        try {
            response = transport.send(call);
        } catch (final TransportFailure ex) {
            throw new QdrantException(ex.getMessage(), ex);
        }
        if(response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new QdrantException("vector store returned HTTP " + response.getStatus()
                + " for " + theMethod + ' ' + thePath + ": " + excerpt(response.getBodyText()));
        }
        try {
            final JsonObject json = JsonParser.parseString(response.getBodyText()).getAsJsonObject();
            if(json.has("status")) {
                final com.google.gson.JsonElement status = json.get("status");
                if(status.isJsonObject() && status.getAsJsonObject().has("error")) {
                    throw new QdrantException("vector store error: " + excerpt(response.getBodyText()));
                }
            }
            return json;
        } catch (final RuntimeException ex) {
            throw new QdrantException("unexpected vector store response: "
                + excerpt(response.getBodyText()), ex);
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
