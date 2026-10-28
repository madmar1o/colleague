package com.mad.edt.colleague.context.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mad.edt.colleague.context.vector.VectorIndex.IndexFailure;
import com.mad.edt.colleague.context.vector.VectorIndex.SourceFile;
import com.mad.edt.colleague.context.vector.VectorIndex.UpdateReport;
import com.mad.edt.colleague.llm.GeneratorGate;

/**
 * The generator gate only makes sense while embedding and chat answer from
 * the same server: the gate exists because one model server swaps models and
 * the live turn pays for every swap. Once the embedding endpoint is a
 * different runtime (its own host:port), a held chat turn no longer competes
 * with embedding, and waiting on the gate would only stall background index
 * passes for no reason - a pass against separated endpoints must never hand
 * the fifteen-second budget to the gate.
 */
class SemanticIndexEndpointTest {

    private static final String COLLECTION = "ws_endpoint";

    private final ScriptedHttpTransport storeTransport = new ScriptedHttpTransport();
    private final ScriptedHttpTransport embedTransport = new ScriptedHttpTransport();

    private VectorIndex index(final GeneratorGate theGate, final int theWaitMs,
        final String theChatRuntimeUrl) {
        return new VectorIndex(COLLECTION,
            new QdrantClient("http://store.test:6333", 1000, storeTransport),
            new EmbeddingClient("http://embed.test:11435", "embed-model", 5000, embedTransport),
            theGate, theWaitMs, theChatRuntimeUrl);
    }

    private void storeOk() {
        storeTransport.answer(200, "{\"result\":true,\"status\":\"ok\"}");
    }

    private void embedOne() {
        embedTransport.answer(200, "{\"embeddings\":[[0.1,0.9]]}");
    }

    private static String numberedFile(final int theLineCount) {
        return IntStream.rangeClosed(1, theLineCount)
            .mapToObj(n -> "line" + n)
            .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("a held chat turn does not stall a pass whose embedding runtime is separate")
    void separatedEndpointsNeverWaitOnTheGate(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        storeOk();
        storeOk();
        embedOne();
        storeOk();

        // the same gate, held right now, and a zero budget: if the pass asked
        // the gate for quiet it would give up instantly, endpoints differ so
        // it must not ask at all
        final UpdateReport report = index(gate, 0, "http://chat.test:11434")
            .update(List.of(new SourceFile(file, "a.bsl")));
        gate.leave();

        assertEquals(1, report.reembedded());
        assertEquals(0, report.failed());
    }

    @Test
    @DisplayName("a shared host:port keeps the gate even when the paths differ")
    void sameHostAndPortStillWaitsOnTheGate(@TempDir final Path theDir) throws IOException {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();

        final IndexFailure failure = assertThrows(IndexFailure.class,
            () -> index(gate, 60, "http://embed.test:11435/v2")
                .update(List.of(new SourceFile(file, "a.bsl"))));

        assertTrue(failure.getMessage().contains("generator busy"), failure.getMessage());
        gate.leave();
    }

    @Test
    @DisplayName("endpoint match is judged by host:port, not by the whole URL")
    void hostAndPortMatchRules() {
        assertTrue(VectorIndex.sharesHostAndPort(
            "http://127.0.0.1:11434", "http://127.0.0.1:11434/api/embed"),
            "same host and port with different paths is one server");
        assertTrue(VectorIndex.sharesHostAndPort(
            "http://127.0.0.1:11434/", "http://127.0.0.1:11434"),
            "trailing slashes and missing paths change nothing");
        assertFalse(VectorIndex.sharesHostAndPort(
            "http://127.0.0.1:11435", "http://127.0.0.1:11434"),
            "the default endpoints are separated by port and must read as separate");
        assertFalse(VectorIndex.sharesHostAndPort(
            "http://gpu.local:11434", "http://cpu.local:11434"),
            "same port on different hosts are different generators");
        assertFalse(VectorIndex.sharesHostAndPort("http://127.0.0.1:11434", ""),
            "an unknown chat endpoint is not a match");
    }
}
