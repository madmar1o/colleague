package com.mad.edt.colleague.context.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A background embedding pass must not occupy the single local generator
 * while a live chat or agent turn is answering: on a small GPU the model
 * server evicts one model to load the other, and every step of the user's
 * turn then pays for that swap. The index therefore asks the generator gate
 * for quiet before re-embedding, gives the pass up when the wait runs out
 * (a later scheduled pass retries), and never waits for files whose
 * fingerprint did not move anyway.
 */
class VectorIndexGateTest {

    private static final String COLLECTION = "ws_gate";
    private static final String SRC_A = "a.bsl";

    private final ScriptedHttpTransport storeTransport = new ScriptedHttpTransport();
    private final ScriptedHttpTransport embedTransport = new ScriptedHttpTransport();

    private VectorIndex index(final GeneratorGate theGate, final int theWaitMs) {
        return new VectorIndex(COLLECTION,
            new QdrantClient("http://store.test:6333", 1000, storeTransport),
            new EmbeddingClient("http://embed.test:11435", "embed-model", 5000, embedTransport),
            theGate, theWaitMs);
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
    @DisplayName("a busy generator past the wait budget ends the pass before any traffic")
    void busyGeneratorAbortsThePass(@TempDir final Path theDir) throws IOException {
        final Path file = theDir.resolve(SRC_A);
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();

        final IndexFailure failure = assertThrows(IndexFailure.class,
            () -> index(gate, 60).update(List.of(new SourceFile(file, SRC_A))));

        assertTrue(failure.getMessage().contains("generator busy"), failure.getMessage());
        assertEquals(0, embedTransport.getCallCount(),
            "the pass must not touch the embedding endpoint while the gate is held");
        assertEquals(0, storeTransport.getCallCount(),
            "the pass must not touch the store either");
        gate.leave();
    }

    @Test
    @DisplayName("a pass waits out a short-lived turn and then embeds")
    void shortBurstIsWaitedOut(@TempDir final Path theDir) throws Exception {
        final Path file = theDir.resolve(SRC_A);
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        final GeneratorGate gate = new GeneratorGate();
        gate.enter();
        final Thread holder = new Thread(() -> {
            try {
                Thread.sleep(120L);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
                // releasing early is fine either way
            }
            gate.leave();
        });
        holder.start();
        storeOk();
        storeOk();
        embedOne();
        storeOk();

        final UpdateReport report = index(gate, 5_000).update(List.of(new SourceFile(file, SRC_A)));
        holder.join();

        assertEquals(1, report.reembedded());
        assertTrue(gate.isIdle());
    }

    @Test
    @DisplayName("unchanged files never wait: the gate matters only for re-embedding")
    void unchangedFilesSkipTheGate(@TempDir final Path theDir) throws IOException, IndexFailure {
        final Path file = theDir.resolve(SRC_A);
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        final GeneratorGate gate = new GeneratorGate();
        storeOk();
        storeOk();
        embedOne();
        storeOk();
        final VectorIndex index = index(gate, 0);
        index.update(List.of(new SourceFile(file, SRC_A)));

        // same content on the same session, gate now held by a live turn: the
        // zero-budget wait would fail instantly if this file needed embedding
        gate.enter();
        final UpdateReport report = index.update(List.of(new SourceFile(file, SRC_A)));
        gate.leave();

        assertEquals(1, report.skipped());
        assertEquals(0, report.failed());
    }

    @Test
    @DisplayName("an index without a gate embeds regardless of other holders")
    void gatelessIndexNeverWaits(@TempDir final Path theDir) throws IOException, IndexFailure {
        final Path file = theDir.resolve(SRC_A);
        Files.writeString(file, numberedFile(5), StandardCharsets.UTF_8);
        storeOk();
        storeOk();
        embedOne();
        storeOk();

        final UpdateReport report = new VectorIndex(COLLECTION,
            new QdrantClient("http://store.test:6333", 1000, storeTransport),
            new EmbeddingClient("http://embed.test:11435", "embed-model", 5000,
                embedTransport)).update(List.of(new SourceFile(file, SRC_A)));

        assertEquals(1, report.reembedded());
    }
}
