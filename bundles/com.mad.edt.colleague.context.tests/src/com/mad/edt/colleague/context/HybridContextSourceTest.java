package com.mad.edt.colleague.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.context.vector.EmbeddingClient;
import com.mad.edt.colleague.context.vector.QdrantClient;
import com.mad.edt.colleague.context.vector.VectorIndex;
import com.mad.edt.colleague.context.vector.VectorIndex.IndexFailure;
import com.mad.edt.colleague.context.vector.VectorIndex.ModuleHit;

/**
 * Pins the hybrid contract the assistant relies on: keyword hits lead, the
 * semantic section only adds chunks of files the keyword part did not quote,
 * any semantic trouble falls back to the plain keyword text without raising,
 * and the semantic section respects its character budget.
 */
class HybridContextSourceTest {

    /** fixed-answer stand-in for a ready vector index */
    private static final class StubIndex extends VectorIndex {

        private final List<ModuleHit> ourHits;
        private final IndexFailure ourFailure;

        StubIndex(final List<ModuleHit> theHits, final IndexFailure theFailure) {
            super("stub", new QdrantClient("http://stub", 10),
                new EmbeddingClient("http://stub", "m", 10));
            ourHits = theHits;
            ourFailure = theFailure;
        }

        @Override
        public List<ModuleHit> searchModules(final String theQuery, final int theLimit,
            final Double theMinScore) throws IndexFailure {
            if(ourFailure != null) {
                throw ourFailure;
            }
            return ourHits;
        }
    }

    /** canned keyword text as the only source */
    private static final class FixedKeyword implements ContextSource {

        private final String ourText;

        FixedKeyword(final String theText) {
            ourText = theText;
        }

        @Override
        public String gather(final String theQuestion) {
            return ourText;
        }
    }

    private static ModuleHit hit(final String thePath, final int theStart, final int theEnd,
        final String theText, final double theScore) {
        return new ModuleHit(thePath, theStart, theEnd, theText, theScore);
    }

    @Test
    void semanticChunksJoinTheKeywordTextAnnotatedWithLinesAndScore() {
        final String keyword = "File: src/a.bsl (line 3)\nkeyword code\n\n";
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(keyword),
            () -> new StubIndex(List.of(
                hit("src/b.bsl", 6, 60, "semantic text", 0.81)), null));
        final String result = source.gather("вопрос");
        assertEquals(keyword + "Semantic module chunks:\n"
            + "File: src/b.bsl (lines 6-60, similarity 0.81)\nsemantic text\n\n", result);
        assertEquals("", source.getLastSemanticError());
    }

    @Test
    void filesAlreadyQuotedByTheKeywordSectionAreSkipped() {
        final String keyword = "File: src/a.bsl (line 3)\nkeyword code\n\n"
            + "File: src/c.bsl\npath only\n\n\n";
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(keyword),
            () -> new StubIndex(List.of(
                hit("src/a.bsl", 6, 60, "duplicate", 0.9),
                hit("src/c.bsl", 1, 55, "also duplicate", 0.8),
                hit("src/d.bsl", 1, 40, "fresh", 0.7)), null));
        final String result = source.gather("q");
        assertTrue(result.contains("fresh"), result);
        assertTrue(!result.contains("duplicate"), result);
    }

    @Test
    void semanticFailureFallsBackSilentlyToTheKeywordText() {
        final String keyword = "File: src/a.bsl (line 1)\nonly keyword\n\n";
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(keyword),
            () -> new StubIndex(List.of(), new IndexFailure("qdrant down", null)));
        assertEquals(keyword, source.gather("q"));
        assertEquals("qdrant down", source.getLastSemanticError());
    }

    @Test
    void missingIndexOrThrowingSupplierStillReturnsKeywordText() {
        final String keyword = "File: src/a.bsl\nx\n\n";
        final HybridContextSource absent =
            new HybridContextSource(new FixedKeyword(keyword), () -> null);
        assertEquals(keyword, absent.gather("q"));
        final HybridContextSource broken = new HybridContextSource(
            new FixedKeyword(keyword), () -> {
                throw new IllegalStateException("not configured");
            });
        assertEquals(keyword, broken.gather("q"));
        assertEquals("not configured", broken.getLastSemanticError());
    }

    @Test
    void emptyKeywordGetsNoSectionHeader() {
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(""),
            () -> new StubIndex(List.of(hit("src/b.bsl", 1, 10, "only semantic", 0.55)), null));
        final String result = source.gather("q");
        assertTrue(result.startsWith("File: src/b.bsl (lines 1-10, similarity 0.55)"), result);
    }

    @Test
    void noSemanticHitsKeepsTheKeywordTextAlone() {
        final String keyword = "File: src/a.bsl\nx\n\n";
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(keyword), () -> new StubIndex(List.of(), null));
        assertEquals(keyword, source.gather("q"));
    }

    @Test
    void semanticSectionStopsAtItsCharacterBudget() {
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(""), () -> new StubIndex(List.of(
                hit("f1.bsl", 1, 55, "x".repeat(800), 0.9),
                hit("f2.bsl", 1, 55, "y".repeat(800), 0.8),
                hit("f3.bsl", 1, 55, "z".repeat(800), 0.7),
                hit("f4.bsl", 1, 55, "w".repeat(800), 0.6),
                hit("f5.bsl", 1, 55, "must not appear", 0.5)), null));
        final String result = source.gather("q");
        assertTrue(result.contains("x".repeat(800)), result);
        assertTrue(result.contains("y".repeat(800)), result);
        assertTrue(!result.contains("must not appear"), result);
        assertTrue(result.length() <= HybridContextSource.SEMANTIC_CHAR_BUDGET,
            String.valueOf(result.length()));
    }

    @Test
    void longChunkTextIsCutToTheCap() {
        final HybridContextSource source = new HybridContextSource(
            new FixedKeyword(""), () -> new StubIndex(List.of(
                hit("a.bsl", 1, 55, "l".repeat(1_200), 0.9)), null));
        final String result = source.gather("q");
        assertTrue(result.contains("l".repeat(900) + "..."), result);
        assertTrue(!result.contains("l".repeat(901)), result);
    }
}
