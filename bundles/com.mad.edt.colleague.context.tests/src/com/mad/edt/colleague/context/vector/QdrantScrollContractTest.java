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
 * handing offsets over. The cursor field is the one the real store answers
 * with - <code>next_page_offset</code>; an invented name silently truncates
 * the walk to the first page.
 */
class QdrantScrollContractTest {

    private static final String COLLECTION = "ws_test";
    private static final String STORE_URL = "http://store.test:6333";

    @Test
    void scrollWalksEveryPageViaTheStoresOwnOffsetCursor() throws QdrantException {
        // the store hands the offset of the last point on each page and null
        // on the last one; a cursor under any other name would silently stop
        // the walk after the first page
        final ScriptedHttpTransport store = new ScriptedHttpTransport()
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"a.bsl\","
                + "\"hash\":\"h1\"}},{\"payload\":{\"path\":\"b.bsl\",\"hash\":\"h2\"}}],"
                + "\"next_page_offset\":\"2\"}}")
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"c.bsl\","
                + "\"hash\":\"h3\"}}],\"next_page_offset\":null}}");
        final List<JsonObject> payloads = new QdrantClient(STORE_URL, 1000, store)
            .scrollPayloads(COLLECTION, List.of("path", "hash"));

        assertEquals(3, payloads.size());
        assertEquals("c.bsl", payloads.get(2).get("path").getAsString());
        // the second page must continue where the first one stopped
        assertTrue(store.callAt(1).getJsonBody().contains("\"offset\":\"2\""),
            store.callAt(1).getJsonBody());
    }

    @Test
    void scrollPagesOverAllPointsCarryingPayloadOnly() throws QdrantException {
        final ScriptedHttpTransport store = new ScriptedHttpTransport()
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"a.bsl\","
                + "\"hash\":\"h1\"}}],\"next_page_offset\":\"10\"}}")
            .answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"b.bsl\","
                + "\"hash\":\"h2\"}}],\"next_page_offset\":null}}");
        final List<JsonObject> payloads = new QdrantClient(STORE_URL, 1000, store)
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
            () -> new QdrantClient(STORE_URL, 1000, store)
                .scrollPayloads(COLLECTION, List.of("path", "hash")));
    }
}
