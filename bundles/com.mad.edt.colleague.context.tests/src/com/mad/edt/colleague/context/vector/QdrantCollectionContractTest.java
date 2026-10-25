package com.mad.edt.colleague.context.vector;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the collection lifecycle contract against a scripted server: the
 * create body carries vector settings at the top level under the plural
 * "vectors" key (other spellings are silently ignored by the real server and
 * leave a vectorless store), an absent collection is created, and the stored
 * dimensionality is verified against what the embedding model produces.
 */
class QdrantCollectionContractTest {

    private static final String COLLECTION = "chunks";

    private static JsonObject describeWithVectorSize(final int theSize) {
        final JsonObject vectors = new JsonObject();
        vectors.addProperty("size", Integer.valueOf(theSize));
        vectors.addProperty("distance", "Cosine");
        final JsonObject params = new JsonObject();
        params.add("vectors", vectors);
        final JsonObject config = new JsonObject();
        config.add("params", params);
        final JsonObject result = new JsonObject();
        result.add("config", config);
        final JsonObject json = new JsonObject();
        json.add("result", result);
        return json;
    }

    @Test
    @DisplayName("an absent collection is created with the exact top-level vectors body")
    void createsAbsentCollection() throws Exception {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(404, "{\"status\":\"error\",\"time\":0}")
            .answer(200, "{\"status\":\"ok\",\"time\":0}")
            .answer(200, describeWithVectorSize(64).toString());

        new QdrantClient("http://store.test", 1_000, transport)
            .ensureCollection(COLLECTION, 64);

        Assertions.assertEquals(3, transport.getCallCount());
        final HttpCallSpec probe = transport.callAt(0);
        Assertions.assertEquals("GET", probe.getMethod());
        Assertions.assertEquals("http://store.test/collections/" + COLLECTION, probe.getUrl());

        final HttpCallSpec create = transport.callAt(1);
        Assertions.assertEquals("PUT", create.getMethod());
        Assertions.assertEquals("http://store.test/collections/" + COLLECTION, create.getUrl());
        Assertions.assertEquals(
            JsonParser.parseString("{\"vectors\":{\"size\":64,\"distance\":\"Cosine\"}}")
                .getAsJsonObject(),
            JsonParser.parseString(create.getJsonBody()).getAsJsonObject());

        Assertions.assertEquals("GET", transport.callAt(2).getMethod());
    }

    @Test
    @DisplayName("an existing collection is not re-created")
    void keepsExistingCollection() throws Exception {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(200, describeWithVectorSize(64).toString())
            .answer(200, describeWithVectorSize(64).toString());

        new QdrantClient("http://store.test", 1_000, transport)
            .ensureCollection(COLLECTION, 64);

        for(final HttpCallSpec call : transport.getCalls()) {
            Assertions.assertNotEquals("PUT", call.getMethod(),
                "must not rewrite an existing collection");
        }
    }

    @Test
    @DisplayName("a dimension mismatch against the stored size is refused")
    void refusesDimensionMismatch() {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(200, describeWithVectorSize(384).toString())
            .answer(200, describeWithVectorSize(384).toString());

        final QdrantClient.QdrantException failure =
            Assertions.assertThrows(QdrantClient.QdrantException.class,
                () -> new QdrantClient("http://store.test", 1_000, transport)
                    .ensureCollection(COLLECTION, 64));
        Assertions.assertTrue(failure.getMessage().contains("384"), failure.getMessage());
        Assertions.assertTrue(failure.getMessage().contains("64"), failure.getMessage());
    }

    @Test
    @DisplayName("a collection left without vector configuration after create is an error")
    void noticesVectorlessCollection() {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(404, "{\"status\":\"error\",\"time\":0}")
            .answer(200, "{\"status\":\"ok\",\"time\":0}")
            .answer(200, "{\"result\":{\"config\":{\"params\":{\"vectors\":null}}}}");

        final QdrantClient.QdrantException failure =
            Assertions.assertThrows(QdrantClient.QdrantException.class,
                () -> new QdrantClient("http://store.test", 1_000, transport)
                    .ensureCollection(COLLECTION, 64));
        Assertions.assertTrue(failure.getMessage().contains("without vector configuration"),
            failure.getMessage());
    }
}
