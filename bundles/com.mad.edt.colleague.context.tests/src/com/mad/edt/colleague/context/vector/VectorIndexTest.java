package com.mad.edt.colleague.context.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mad.edt.colleague.context.vector.VectorIndex.IndexFailure;
import com.mad.edt.colleague.context.vector.VectorIndex.ModuleHit;
import com.mad.edt.colleague.context.vector.VectorIndex.SourceFile;
import com.mad.edt.colleague.context.vector.VectorIndex.UpdateReport;

/**
 * Pins the update and search contracts of the vector index against scripted
 * stand-ins for the store and the embedding runtime: unchanged files cause no
 * traffic, changed files are always cleared before their fresh windows are
 * written, vanished files get purged, and the search path embeds the question
 * exactly once before the similarity request.
 */
class VectorIndexTest {

    private static final String COLLECTION = "ws_test";

    private final ScriptedHttpTransport storeTransport = new ScriptedHttpTransport();
    private final ScriptedHttpTransport embedTransport = new ScriptedHttpTransport();

    private VectorIndex index() {
        return new VectorIndex(COLLECTION,
            new QdrantClient("http://store.test:6333", 1000, storeTransport),
            new EmbeddingClient("http://embed.test:11435", "embed-model", 5000, embedTransport));
    }

    private void storeOk() {
        storeTransport.answer(200, "{\"result\":true,\"status\":\"ok\"}");
    }

    /** one vector per text, each vector two components */
    private void embedVectors(final int theCount) {
        final String vectors = IntStream.range(0, theCount)
            .mapToObj(n -> "[0." + (n + 1) + ",0.9]")
            .collect(Collectors.joining(",", "[", "]"));
        embedTransport.answer(200, "{\"embeddings\":" + vectors + "}");
    }

    private static String numberedFile(final int theLineCount) {
        return IntStream.rangeClosed(1, theLineCount)
            .mapToObj(n -> "line" + n)
            .collect(Collectors.joining("\n"));
    }

    private static SourceFile sourceOf(final Path theFile, final String theDisplayPath) {
        return new SourceFile(theFile, theDisplayPath);
    }

    /** same digest the index itself uses, so a stored payload hash is reusable */
    private static String sha256Hex(final byte[] theContent) {
        try {
            final java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
            final StringBuilder hex = new StringBuilder();
            for(final byte part : digest.digest(theContent)) {
                hex.append(String.format("%02x", Integer.valueOf(part & 0xff)));
            }
            return hex.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void firstPassClearsPointsBeforeWritingEvenForANewFile(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        // the session may have restarted while the collection still holds an
        // older generation of the same file, so the clear always precedes
        // the write
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(60), StandardCharsets.UTF_8);
        storeOk();
        embedVectors(2);
        storeOk();
        final UpdateReport report =
            index().update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(1, report.reembedded());
        assertEquals(0, report.skipped());
        assertEquals(2, storeTransport.getCallCount());
        assertEquals("POST", storeTransport.callAt(0).getMethod());
        assertTrue(storeTransport.callAt(0).getUrl().endsWith("/points/delete?wait=true"),
            storeTransport.callAt(0).getUrl());
        assertTrue(storeTransport.callAt(0).getJsonBody().contains("a.bsl"),
            storeTransport.callAt(0).getJsonBody());
        assertEquals("PUT", storeTransport.callAt(1).getMethod());
        assertTrue(storeTransport.callAt(1).getUrl().endsWith("/points?wait=true"),
            storeTransport.callAt(1).getUrl());
        assertEquals(1, embedTransport.getCallCount());
    }

    @Test
    void unchangedFileIsSkippedWithoutASingleCall(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(30), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        embedVectors(1);
        storeOk();
        ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        final int storeCalls = storeTransport.getCallCount();
        final int embedCalls = embedTransport.getCallCount();
        final UpdateReport second =
            ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(0, second.reembedded());
        assertEquals(1, second.skipped());
        assertEquals(storeCalls, storeTransport.getCallCount());
        assertEquals(embedCalls, embedTransport.getCallCount());
    }

    @Test
    void rewrittenFileIsClearedBeforeTheFreshWindowsAreWritten(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(30), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        embedVectors(1);
        storeOk();
        ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        // 56 content lines: two windows, so the second pass needs two vectors
        Files.writeString(file, numberedFile(55) + "\nextra", StandardCharsets.UTF_8);
        storeOk();
        embedVectors(2);
        storeOk();
        final UpdateReport second = ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(1, second.reembedded());
        // this pass: call 0 clears, call 1 writes - never the other way round
        assertEquals("POST", storeTransport.callAt(2).getMethod());
        assertTrue(storeTransport.callAt(2).getUrl().endsWith("/points/delete?wait=true"));
        assertEquals("PUT", storeTransport.callAt(3).getMethod());
        assertTrue(storeTransport.callAt(3).getUrl().endsWith("/points?wait=true"));
        assertTrue(storeTransport.callAt(3).getJsonBody().contains("line55"));
    }

    @Test
    void fileThatVanishedFromTheListIsPurged(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path keep = theDir.resolve("keep.bsl");
        final Path gone = theDir.resolve("gone.bsl");
        Files.writeString(keep, numberedFile(10), StandardCharsets.UTF_8);
        Files.writeString(gone, numberedFile(10), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        embedVectors(1);
        storeOk();
        storeOk();
        embedVectors(1);
        storeOk();
        ourIndex.update(List.of(sourceOf(keep, "keep.bsl"), sourceOf(gone, "gone.bsl")));
        assertEquals(2, ourIndex.trackedFileCount());
        // only keep is listed now, and its content did not move: the single
        // store call of this pass must purge the vanished file
        storeOk();
        final UpdateReport second = ourIndex.update(List.of(sourceOf(keep, "keep.bsl")));
        assertEquals(0, second.reembedded());
        assertEquals(1, second.skipped());
        assertEquals(1, second.purged());
        final String purgeBody = storeTransport.callAt(4).getJsonBody();
        assertTrue(purgeBody.contains("gone.bsl"), purgeBody);
        assertTrue(storeTransport.callAt(4).getUrl().endsWith("/points/delete?wait=true"));
        assertEquals(1, ourIndex.trackedFileCount());
    }

    @Test
    void fileThatWentBlankKeepsOnlyTheClearingDelete(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(10), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        embedVectors(1);
        storeOk();
        ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        Files.writeString(file, "", StandardCharsets.UTF_8);
        storeOk();
        final UpdateReport second = ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(1, second.reembedded());
        // only one further store call: the stale-points delete; the blank
        // file produces no windows, so no embedding batch and no write
        assertEquals(3, storeTransport.getCallCount());
        assertEquals(1, embedTransport.getCallCount());
        assertTrue(storeTransport.callAt(2).getUrl().endsWith("/points/delete?wait=true"));
    }

    @Test
    void mismatchedEmbeddingVectorCountSkipsTheFileAndKeepsItUntracked(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(60), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        // the runtime answers one vector for the two expected chunks
        embedTransport.answer(200, "{\"embeddings\":[[1.0,2.0]]}");
        final UpdateReport report = ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(0, report.reembedded());
        assertEquals(1, report.failed());
        // no write happened, the file stays untracked so a retry re-embeds it
        assertEquals(1, storeTransport.getCallCount());
        assertEquals(0, ourIndex.trackedFileCount());
    }

    @Test
    void oneFailingFileDoesNotSinkTheWholePass(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        // 57 000-file workspaces hit transient runtime hiccups: one broken
        // file must cost its own pass only, the neighbors still get indexed
        final Path good1 = theDir.resolve("g1.bsl");
        final Path bad = theDir.resolve("bad.bsl");
        final Path good2 = theDir.resolve("g2.bsl");
        Files.writeString(good1, numberedFile(10), StandardCharsets.UTF_8);
        Files.writeString(bad, numberedFile(10), StandardCharsets.UTF_8);
        Files.writeString(good2, numberedFile(10), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        // g1: clear, embed, write
        storeOk();
        embedVectors(1);
        storeOk();
        // bad: its stale points are cleared, then the embed refuses
        storeOk();
        embedTransport.answer(500, "{\"error\":\"runtime hiccup\"}");
        // g2: clear, embed, write
        storeOk();
        embedVectors(1);
        storeOk();
        final UpdateReport report = ourIndex.update(
            List.of(sourceOf(good1, "g1.bsl"), sourceOf(bad, "bad.bsl"),
                sourceOf(good2, "g2.bsl")));
        assertEquals(2, report.reembedded());
        assertEquals(0, report.skipped());
        assertEquals(1, report.failed());
        // the two neighbors are tracked, the failed file stays retryable
        assertEquals(2, ourIndex.trackedFileCount());
        assertNotNull(ourIndex.fingerprintOf("g1.bsl"));
        assertNull(ourIndex.fingerprintOf("bad.bsl"));
    }

    @Test
    void repeatedFileFailuresOpenTheCircuitAndAbortThePass(@TempDir final Path theDir)
        throws IOException {
        // when the store itself is down, burning one request per remaining
        // file is rude: after a few consecutive failures the pass gives up
        final List<SourceFile> files = new java.util.ArrayList<>();
        for(int n = 0; n < 12; n++) {
            final Path file = theDir.resolve("f" + n + ".bsl");
            Files.writeString(file, numberedFile(10), StandardCharsets.UTF_8);
            files.add(sourceOf(file, file.getFileName().toString()));
        }
        for(int n = 0; n < VectorIndex.MAX_CONSECUTIVE_FILE_FAILURES; n++) {
            // every file trips over the same refused store request
            storeTransport.answer(500, "{\"status\":{\"error\":\"store down\"}}");
        }
        final IndexFailure failure = assertThrows(IndexFailure.class,
            () -> index().update(files));
        assertTrue(failure.getMessage().contains("consecutive"), failure.getMessage());
        // each failing file cost exactly one store request, then the walk stopped
        assertEquals(VectorIndex.MAX_CONSECUTIVE_FILE_FAILURES, storeTransport.getCallCount());
        assertEquals(0, embedTransport.getCallCount());
    }

    @Test
    void restoredFingerprintsMakeARebootedSessionSkipStoredFiles(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        // the store already remembers every chunk of a file including its
        // content hash: a fresh session adopts those fingerprints instead of
        // re-embedding the whole workspace from scratch
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(30), StandardCharsets.UTF_8);
        final String hash = sha256Hex(numberedFile(30).getBytes(StandardCharsets.UTF_8));
        storeTransport.answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"a.bsl\","
            + "\"hash\":\"" + hash + "\"}}],\"next_page_offset\":null}}");
        final VectorIndex ourIndex = index();
        ourIndex.restoreFingerprints();
        assertEquals(1, ourIndex.trackedFileCount());

        final int storeCalls = storeTransport.getCallCount();
        final UpdateReport report = ourIndex.update(
            List.of(sourceOf(file, "a.bsl")));
        assertEquals(0, report.reembedded());
        assertEquals(1, report.skipped());
        assertEquals(0, report.failed());
        // skipping is decided without any further store or embed traffic
        assertEquals(storeCalls, storeTransport.getCallCount());
        assertEquals(0, embedTransport.getCallCount());
    }

    @Test
    void forgetFingerprintsForcesAFullReembed(@TempDir final Path theDir)
        throws IOException, IndexFailure {
        final Path file = theDir.resolve("a.bsl");
        Files.writeString(file, numberedFile(30), StandardCharsets.UTF_8);
        final VectorIndex ourIndex = index();
        storeOk();
        embedVectors(1);
        storeOk();
        ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        ourIndex.forgetFingerprints();
        storeOk();
        embedVectors(1);
        storeOk();
        final UpdateReport second = ourIndex.update(List.of(sourceOf(file, "a.bsl")));
        assertEquals(1, second.reembedded());
        assertEquals(0, second.skipped());
        assertEquals(4, storeTransport.getCallCount());
        assertEquals(2, embedTransport.getCallCount());
    }

    @Test
    void searchEmbedsTheQuestionOnceThenAsksTheStore() throws IndexFailure {
        embedVectors(1);
        storeTransport.answer(200, "{\"result\":["
            + "{\"score\":0.81,\"payload\":{\"path\":\"src/a.bsl\",\"startLine\":6,"
            + "\"endLine\":60,\"text\":\"chunk text\"}}]}");
        final List<ModuleHit> hits =
            index().searchModules("what makes the total", 4, Double.valueOf(0.35));
        assertEquals(1, embedTransport.getCallCount());
        assertTrue(embedTransport.callAt(0).getJsonBody().contains("what makes the total"));
        final String searchBody = storeTransport.callAt(0).getJsonBody();
        assertTrue(searchBody.contains("\"limit\":4"), searchBody);
        assertTrue(searchBody.contains("\"score_threshold\":0.35"), searchBody);
        assertEquals(1, hits.size());
        assertEquals("src/a.bsl", hits.get(0).path());
        assertEquals(6, hits.get(0).startLine());
        assertEquals(60, hits.get(0).endLine());
        assertEquals("chunk text", hits.get(0).text());
        assertEquals(0.81, hits.get(0).score(), 0.0001);
    }

    @Test
    void ensureReadyProbesTheModelBeforeTouchingTheCollection() throws IndexFailure {
        embedVectors(1);
        storeTransport.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        storeTransport.answer(200, "{\"result\":{\"config\":{\"params\":{\"vectors\":"
            + "{\"size\":2,\"distance\":\"Cosine\"}}}}}");
        index().ensureReady();
        // the probe call came first, only then the collection was verified
        assertEquals(1, embedTransport.getCallCount());
        assertEquals(2, storeTransport.getCallCount());
    }

    @Test
    void ensureReadyRefusesWhenTheStoredVectorsHaveAnotherSize() {
        embedVectors(1);
        storeTransport.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        storeTransport.answer(200, "{\"result\":{\"config\":{\"params\":{\"vectors\":"
            + "{\"size\":768,\"distance\":\"Cosine\"}}}}}");
        final IndexFailure failure = assertThrows(IndexFailure.class,
            () -> index().ensureReady());
        assertTrue(failure.getMessage().contains("size 768"), failure.getMessage());
        // no creation attempt: the probe alone decided
        assertEquals(2, storeTransport.getCallCount());
    }
}
