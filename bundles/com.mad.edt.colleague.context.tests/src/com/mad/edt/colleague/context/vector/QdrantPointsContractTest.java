package com.mad.edt.colleague.context.vector;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the point-level request shapes: waits are requested on every write so
 * later reads see the data, deletes carry the exact Rust-store filter form,
 * and search answers become payload-plus-score hits.
 */
class QdrantPointsContractTest {

    private static final String COLLECTION = "chunks";

    @Test
    @DisplayName("upsert sends id, vector and payload and waits for the write")
    void upsertShape() throws Exception {
        final ScriptedHttpTransport transport =
            new ScriptedHttpTransport().answer(200, "{\"status\":\"ok\"}");

        final JsonObject payload = new JsonObject();
        payload.addProperty("file", "src/a.bsl");
        final String id = QdrantClient.pointId("src/a.bsl", 0);
        new QdrantClient("http://store.test", 1_000, transport).upsert(COLLECTION,
            List.of(new QdrantClient.Point(id, new float[] {0.25f, 0.5f}, payload)));

        final HttpCallSpec call = transport.callAt(0);
        Assertions.assertEquals("PUT", call.getMethod());
        Assertions.assertEquals("http://store.test/collections/" + COLLECTION
            + "/points?wait=true", call.getUrl());
        Assertions.assertEquals(
            JsonParser.parseString("{\"points\":[{\"id\":\"" + id
                + "\",\"vector\":[0.25,0.5],\"payload\":{\"file\":\"src/a.bsl\"}}]}")
                .getAsJsonObject(),
            JsonParser.parseString(call.getJsonBody()).getAsJsonObject());
    }

    @Test
    @DisplayName("delete-by-field posts the must/match filter and waits")
    void deleteFilterShape() throws Exception {
        final ScriptedHttpTransport transport =
            new ScriptedHttpTransport().answer(200, "{\"status\":\"ok\",\"result\":{}}");

        new QdrantClient("http://store.test", 1_000, transport)
            .deleteByField(COLLECTION, "file", "src/a b.txt");

        final HttpCallSpec call = transport.callAt(0);
        Assertions.assertEquals("POST", call.getMethod());
        Assertions.assertEquals("http://store.test/collections/" + COLLECTION
            + "/points/delete?wait=true", call.getUrl());
        Assertions.assertEquals(
            JsonParser.parseString(
                "{\"filter\":{\"must\":[{\"match\":{\"key\":\"file\","
                    + "\"value\":\"src/a b.txt\"}}]}}").getAsJsonObject(),
            JsonParser.parseString(call.getJsonBody()).getAsJsonObject());
    }

    @Test
    @DisplayName("search sends the query vector and turns answers into payload hits")
    void searchShapeAndParsing() throws Exception {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(200, "{\"result\":["
                + "{\"score\":0.9,\"payload\":{\"file\":\"x.bsl\"}},"
                + "{\"score\":0.4,\"payload\":{\"file\":\"y.bsl\"}}]}");

        final List<QdrantClient.Hit> hits = new QdrantClient("http://store.test", 1_000, transport)
            .search(COLLECTION, new float[] {0.1f, 0.2f}, 5, Double.valueOf(0.3));

        final HttpCallSpec call = transport.callAt(0);
        Assertions.assertEquals("POST", call.getMethod());
        Assertions.assertEquals("http://store.test/collections/" + COLLECTION
            + "/points/search", call.getUrl());
        Assertions.assertEquals(
            JsonParser.parseString("{\"vector\":[0.1,0.2],\"limit\":5,"
                + "\"with_payload\":true,\"score_threshold\":0.3}").getAsJsonObject(),
            JsonParser.parseString(call.getJsonBody()).getAsJsonObject());

        Assertions.assertEquals(2, hits.size());
        Assertions.assertEquals("x.bsl", hits.get(0).getPayload().get("file").getAsString());
        Assertions.assertEquals(0.9, hits.get(0).getScore(), 1e-9);
    }

    @Test
    @DisplayName("point ids are stable UUIDs per path and chunk number")
    void stablePointIds() {
        final String first = QdrantClient.pointId("src/a.bsl", 3);
        Assertions.assertEquals(first, QdrantClient.pointId("src/a.bsl", 3));
        Assertions.assertNotEquals(first, QdrantClient.pointId("src/a.bsl", 4));
        Assertions.assertTrue(first.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), first);
    }
}
