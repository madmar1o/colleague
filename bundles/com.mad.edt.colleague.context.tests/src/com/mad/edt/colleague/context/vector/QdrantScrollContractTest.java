package com.mad.edt.colleague.context.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.context.vector.QdrantClient.QdrantException;

/**
 * Pins the scroll contract the fingerprint restore relies on: the walk pages
 * over the whole point set, asks for payload only (never vectors), passes the
 * previous page offset into the next request and stops when the store stops
 * handing offsets over.
 */
class QdrantScrollContractTest {

    private static final String COLLECTION = "ws_test";

    @Test
    void scrollPagesOverAllPointsCarryingPayloadOnly() throws QdrantException {
        final ScriptedHttpTransport store = new ScriptedHttpTransport()
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"a.bsl\","
                + "\"hash\":\"h1\"}}],\"next_page\":\"10\"}}")
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"b.bsl\","
                + "\"hash\":\"h2\"}}],\"next_page\":null}}");
        final List<JsonObject> payloads = new QdrantClient("http://store.test:6333", 1000, store)
            .scrollPayloads(COLLECTION, List.of("path", "hash"));

        assertEquals(2, payloads.size());
        assertEquals("a.bsl", payloads.get(0).get("path").getAsString());
        assertEquals("h2", payloads.get(1).get("hash").getAsString());

        final String firstBody = store.callAt(0).getJsonBody();
        assertTrue(store.callAt(0).getUrl().endsWith("/points/scroll"),
            store.callAt(0).getUrl());
        assertTrue(firstBody.contains("\"with_vector\":false"), firstBody);
        assertTrue(firstBody.contains("\"path\""), firstBody);
        assertTrue(firstBody.contains("\"hash\""), firstBody);
        // the second page must continue where the first one stopped
        assertTrue(store.callAt(1).getJsonBody().contains("\"offset\":\"10\""),
            store.callAt(1).getJsonBody());
    }

    @Test
    void refusedScrollSurfacesAsStoreFailure() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport()
            .answer(500, "{\"status\":{\"error\":\"boom\"}}");
        assertThrows(QdrantException.class,
            () -> new QdrantClient("http://store.test:6333", 1000, store)
                .scrollPayloads(COLLECTION, List.of("path", "hash")));
    }
}
