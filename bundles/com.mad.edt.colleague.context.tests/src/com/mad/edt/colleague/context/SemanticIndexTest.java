package com.mad.edt.colleague.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mad.edt.colleague.context.vector.ScriptedHttpTransport;
import com.mad.edt.colleague.context.vector.VectorIndex.UpdateReport;

/**
 * Pins the seams the UI wiring relies on: collection naming survives any
 * project name, the index refuses to exist while the settings are empty, and
 * a scripted session prepares, refreshes and answers the hybrid layer through
 * the expected call sequence.
 */
class SemanticIndexTest {

    /** keyword-only stand-in so hybrid merging is observable */
    private static final class KeywordStub implements ContextSource {

        private final String ourText;

        KeywordStub(final String theText) {
            ourText = theText;
        }

        @Override
        public String gather(final String theQuestion) {
            return ourText;
        }
    }

    private static void probeFourDimensions(final ScriptedHttpTransport theEmbed) {
        theEmbed.answer(200, "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}");
    }

    private static void storeCollectionOk(final ScriptedHttpTransport theStore,
        final int theSize) {
        theStore.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        theStore.answer(200, "{\"result\":{\"config\":{\"params\":{\"vectors\":"
            + "{\"size\":" + theSize + ",\"distance\":\"Cosine\"}}}}}");
    }

    private static boolean readyFlag(final SemanticIndex theIndex) {
        return theIndex.readyIndex() != null;
    }

    @Test
    void collectionNamesStayStoreSafeForAnyProjectName() {
        assertEquals("colleague_er_puh", SemanticIndex.collectionFor("er puh"));
        assertEquals("colleague_a-b_c", SemanticIndex.collectionFor("a-b/c"));
        // names outside the store safe alphabet are replaced, never dropped
        assertEquals("colleague_" + "_".repeat(6), SemanticIndex.collectionFor("проект"));
        // an empty project name still yields a usable collection
        assertEquals("colleague_workspace", SemanticIndex.collectionFor("  "));
        // very long names are cut, the store caps collection length
        assertEquals("colleague_" + "x".repeat(SemanticIndex.PROJECT_NAME_BUDGET),
            SemanticIndex.collectionFor("x".repeat(500)));
    }

    @Test
    void unconfiguredSettingsProduceNoIndex() {
        // no test in this suite configures the static settings, so a fresh
        // session starts with the semantic layer off
        if(!SemanticSettings.isConfigured()) {
            assertNull(SemanticIndex.tryCreate());
        }
    }

    @Test
    void scriptedSessionPreparesAfterProbingTheModelFirst() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index =
            SemanticIndex.forTesting("colleague_test", store, embed);
        assertEquals("colleague_test", index.getCollection());
        // not prepared yet: unready, and the hybrid keeps keyword text alone
        assertFalse(index.isReady());
        assertNull(index.readyIndex());
        final HybridContextSource hybrid = new HybridContextSource(
            new KeywordStub("File: m/y.bsl (line 1)\nkeyword hit\n\n"), index::readyIndex);
        assertEquals("File: m/y.bsl (line 1)\nkeyword hit\n\n", hybrid.gather("q"));
        // the failed attempt touched no endpoint: the supplier said null
        assertEquals(0, embed.getCallCount());
        assertEquals(0, store.getCallCount());

        // prepare: dimension probe first, then the two collection checks,
        // then the fingerprint walk over the stored points
        probeFourDimensions(embed);
        storeCollectionOk(store, 4);
        store.answer(200, "{\"result\":{\"points\":[],\"next_page_offset\":null}}");
        index.prepare();
        assertTrue(index.isReady(), index.getStatus());
        assertTrue(readyFlag(index));
        assertEquals(1, embed.getCallCount());
        assertEquals(3, store.getCallCount());
        // a second pass on a ready index needs no traffic at all
        index.prepare();
        assertEquals(1, embed.getCallCount());
        assertEquals(3, store.getCallCount());
        assertNotNull(index.readyIndex());
    }

    @Test
    void prepareFailureLeavesTheIndexUnreadyAndNamesTheEndpoint() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index = SemanticIndex.forTesting("ws", store, embed);
        // the embedding endpoint answers an error: no vector comes back
        embed.answer(500, "{\"error\":\"model not found\"}");
        index.prepare();
        assertFalse(index.isReady());
        assertTrue(index.getStatus().contains("model not found"), index.getStatus());
        assertNull(index.readyIndex());
        // the store was never touched while the probe failed
        assertEquals(0, store.getCallCount());
        // retrying after a failure is allowed and re-probes the endpoints
        embed.answer(200, "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}");
        storeCollectionOk(store, 4);
        store.answer(200, "{\"result\":{\"points\":[],\"next_page_offset\":null}}");
        index.prepare();
        assertTrue(index.isReady(), index.getStatus());
        // the hybrid layer then sees an index
        assertTrue(readyFlag(index));
    }

    @Test
    void unpreparedHybridKeepsTheKeywordTextIntact() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index = SemanticIndex.forTesting("ws", store, embed);
        embed.answer(500, "{\"error\":\"model not found\"}");
        index.prepare();
        final HybridContextSource hybrid =
            new HybridContextSource(new KeywordStub("keyword only\n"), index::readyIndex);
        assertEquals("keyword only\n", hybrid.gather("q"));
        assertEquals(0, store.getCallCount());
    }

    @Test
    void refreshOnAnUnreadyIndexFailsLoudlyForTheJobLog() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index = SemanticIndex.forTesting("ws", store, embed);
        embed.answer(500, "{}");
        index.prepare(); // records the failure, stays unready
        // the refresh re-probes the endpoints first, and that retry fails too
        embed.answer(500, "{\"error\":\"still down\"}");
        final SemanticIndex.SemanticFailure failure = assertThrows(
            SemanticIndex.SemanticFailure.class,
            () -> index.refreshFrom(acceptor -> {
            }));
        assertTrue(failure.getMessage().contains("unavailable"), failure.getMessage());
        // the logging variant keeps the same text without raising for the job
        embed.answer(500, "{\"error\":\"still down\"}");
        assertTrue(index.refreshAndLog(acceptor -> {
        }).contains("refresh failed: semantic layer unavailable"));
        // the failed retries never reached the store either
        assertEquals(0, store.getCallCount());
    }

    @Test
    void dimensionMismatchKeepsTheLayerUnreadyAndTheHybridSilent() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index = SemanticIndex.forTesting("ws", store, embed);
        probeFourDimensions(embed);
        // the stored collection was built for another model
        storeCollectionOk(store, 384);
        index.prepare();
        assertFalse(index.isReady());
        assertTrue(index.getStatus().contains("size 384"), index.getStatus());
        final HybridContextSource hybrid =
            new HybridContextSource(new KeywordStub("plain keyword text\n"), index::readyIndex);
        assertEquals("plain keyword text\n", hybrid.gather("q"));
    }

    @Test
    void preparedCollectionSurvivesTheFullRefreshCycle(@TempDir final Path theDir)
        throws Exception {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index =
            SemanticIndex.forTesting("colleague_cycle", store, embed);
        probeFourDimensions(embed);
        storeCollectionOk(store, 4);
        store.answer(200, "{\"result\":{\"points\":[],\"next_page_offset\":null}}");
        index.prepare();
        assertTrue(index.isReady(), index.getStatus());

        final Path file = theDir.resolve("x.bsl");
        Files.writeString(file, "line1\nline2\nline3");
        final SemanticIndex.FileSource sources = acceptor -> acceptor.accept(file, "src/x.bsl");

        // first pass: clear the stale points, embed the one window, write it
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        embed.answer(200, "{\"embeddings\":[[0.5,0.5,0.5,0.5]]}");
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        UpdateReport report = index.refreshFrom(sources);
        assertEquals(1, report.reembedded());
        assertEquals(0, report.skipped());
        // second pass: same content, so not a single further call is made
        report = index.refreshFrom(sources);
        assertEquals(0, report.reembedded());
        assertEquals(1, report.skipped());

        // search through the hybrid layer quotes the stored chunk after the
        // keyword section and skips files the keyword part already showed;
        // withKeyword is exactly the seam the UI wires into a question
        embed.answer(200, "{\"embeddings\":[[0.5,0.5,0.5,0.5]]}");
        store.answer(200, "{\"result\":[{\"score\":0.66,\"payload\":{\"path\":\"src/x.bsl\","
            + "\"startLine\":1,\"endLine\":3,\"text\":\"chunk from the store\"}}]}");
        final ContextSource hybrid = index.withKeyword(
            new KeywordStub("File: m/y.bsl (line 1)\nkeyword hit\n\n"));
        assertTrue(hybrid instanceof HybridContextSource);
        final String merged = hybrid.gather("how does the total add up");
        assertTrue(merged.contains("keyword hit"), merged);
        assertTrue(merged.contains("Semantic module chunks:"), merged);
        assertTrue(merged.contains("chunk from the store"), merged);
        assertTrue(merged.contains("similarity 0.66"), merged);
        assertEquals("", ((HybridContextSource) hybrid).getLastSemanticError());
    }

    @Test
    void prepareAdoptsFingerprintsSoARebootedSessionSkipsStoredFiles(@TempDir final Path theDir)
        throws Exception {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index =
            SemanticIndex.forTesting("colleague_reboot", store, embed);
        final Path file = theDir.resolve("x.bsl");
        Files.writeString(file, "line1\nline2\nline3");
        // the store payload of yesterday's session carries the file hash and
        // the absolute location the chunk belongs to
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        store.answer(200, "{\"result\":{\"config\":{\"params\":{\"vectors\":"
            + "{\"size\":4,\"distance\":\"Cosine\"}}}}}");
        store.answer(200, "{\"result\":{\"points\":[{\"payload\":{\"path\":\"src/x.bsl\","
            + "\"location\":\"" + file.toString().replace("\\", "\\\\") + "\","
            + "\"hash\":\"" + sha256Hex(Files.readAllBytes(file))
            + "\"}}],\"next_page_offset\":null}}");
        probeFourDimensions(embed);
        index.prepare();
        assertTrue(index.isReady(), index.getStatus());

        final SemanticIndex.FileSource sources = acceptor -> acceptor.accept(file, "src/x.bsl");
        final UpdateReport report = index.refreshFrom(sources);
        assertEquals(0, report.reembedded());
        assertEquals(1, report.skipped());
        // the whole cycle after the reboot stayed silent: prepare traffic only
        assertEquals(1, embed.getCallCount());
        assertEquals(3, store.getCallCount());
    }

    @Test
    void fingerprintRestoreFailureKeepsTheLayerReady() {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index = SemanticIndex.forTesting("ws", store, embed);
        probeFourDimensions(embed);
        storeCollectionOk(store, 4);
        // the walk over stored points trips over a refused request
        store.answer(500, "{\"status\":{\"error\":\"scroll failed\"}}");
        index.prepare();
        // a broken restore only costs the shortcut, never the layer itself
        assertTrue(index.isReady(), index.getStatus());
        assertTrue(index.getStatus().contains("fingerprints not restored"),
            index.getStatus());
    }

    @Test
    void oneBadFileLeavesTheRefreshPassSuccessful(@TempDir final Path theDir)
        throws Exception {
        final ScriptedHttpTransport store = new ScriptedHttpTransport();
        final ScriptedHttpTransport embed = new ScriptedHttpTransport();
        final SemanticIndex index =
            SemanticIndex.forTesting("colleague_badfile", store, embed);
        probeFourDimensions(embed);
        storeCollectionOk(store, 4);
        store.answer(200, "{\"result\":{\"points\":[],\"next_page_offset\":null}}");
        index.prepare();

        final Path good = theDir.resolve("g.bsl");
        final Path bad = theDir.resolve("b.bsl");
        Files.writeString(good, "line1\nline2\nline3");
        Files.writeString(bad, "line1\nline2\nline3");
        final SemanticIndex.FileSource sources = acceptor -> {
            acceptor.accept(good, "g.bsl");
            acceptor.accept(bad, "b.bsl");
        };
        // good: clear, embed, write; bad: clear, then the embed refuses
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        embed.answer(200, "{\"embeddings\":[[0.5,0.5,0.5,0.5]]}");
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        embed.answer(500, "{\"error\":\"runtime hiccup\"}");
        final UpdateReport report = index.refreshFrom(sources);
        assertEquals(1, report.reembedded());
        assertEquals(1, report.failed());
        // the retry pass: the tracked good file is silent, the bad one trips
        // once more, and the log line tells about the failure
        store.answer(200, "{\"result\":true,\"status\":\"ok\"}");
        embed.answer(500, "{\"error\":\"runtime hiccup\"}");
        final String logLine = index.refreshAndLog(sources);
        assertTrue(logLine.contains("failed 1"), logLine);
    }

    private static String sha256Hex(final byte[] theContent) throws Exception {
        final java.security.MessageDigest digest =
            java.security.MessageDigest.getInstance("SHA-256");
        final StringBuilder hex = new StringBuilder();
        for(final byte part : digest.digest(theContent)) {
            hex.append(String.format("%02x", Integer.valueOf(part & 0xff)));
        }
        return hex.toString();
    }
}
