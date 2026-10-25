package com.mad.edt.colleague.context.vector;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.mad.edt.colleague.http.HttpCallSpec;

/**
 * Pins the embedding batch contract: one request per batch with the model and
 * the input array, vectors returned in input order, and a wrong vector count
 * treated as a hard failure rather than silent truncation.
 */
class EmbeddingBatchContractTest {

    private static EmbeddingClient client(final ScriptedHttpTransport theTransport) {
        return new EmbeddingClient("http://runtime.test", "emb-model", 5_000, theTransport);
    }

    @Test
    @DisplayName("one batch request carries model and inputs, answers keep the order")
    void batchRoundTrip() throws Exception {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(200, "{\"embeddings\":[[0.1,0.2],[0.3,0.4]]}");

        final List<float[]> vectors = client(transport).embed(List.of("first", "second"));

        final HttpCallSpec call = transport.callAt(0);
        Assertions.assertEquals("POST", call.getMethod());
        Assertions.assertEquals("http://runtime.test/api/embed", call.getUrl());
        Assertions.assertEquals(
            JsonParser.parseString("{\"model\":\"emb-model\",\"input\":[\"first\",\"second\"]}")
                .getAsJsonObject(),
            JsonParser.parseString(call.getJsonBody()).getAsJsonObject());

        Assertions.assertEquals(2, vectors.size());
        Assertions.assertEquals(2, vectors.get(0).length);
        Assertions.assertEquals(0.1f, vectors.get(0)[0], 1e-6f);
        Assertions.assertEquals(0.4f, vectors.get(1)[1], 1e-6f);
    }

    @Test
    @DisplayName("a response with the wrong vector count is refused")
    void refusesCountMismatch() {
        final ScriptedHttpTransport transport = new ScriptedHttpTransport()
            .answer(200, "{\"embeddings\":[[0.1,0.2]]}");
        final EmbeddingClient.EmbeddingException failure =
            Assertions.assertThrows(EmbeddingClient.EmbeddingException.class,
                () -> client(transport).embed(List.of("first", "second")));
        Assertions.assertTrue(failure.getMessage().contains("1 vectors for 2 texts"),
            failure.getMessage());
    }

    @Test
    @DisplayName("an HTTP error from the runtime surfaces as an embedding failure")
    void reportsHttpError() {
        final ScriptedHttpTransport transport =
            new ScriptedHttpTransport().answer(500, "model is loading");
        final EmbeddingClient.EmbeddingException failure =
            Assertions.assertThrows(EmbeddingClient.EmbeddingException.class,
                () -> client(transport).embed(List.of("text")));
        Assertions.assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        Assertions.assertTrue(failure.getMessage().contains("model is loading"),
            failure.getMessage());
    }
}
