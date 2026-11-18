package com.mad.edt.colleague.context.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the search rules before and after the class split: query tokenizing,
 * name and line scoring points, the best line of a file, excerpts around the
 * best line with their length caps, the final block format and character
 * budget, and the coverage note when the time or read budget cuts a scan.
 */
class SearchRulesTest {

    // shared fixture names and the query that drives every budget test below
    private static final String QUERY = "target";
    private static final String IGNORED_DIR = "ignored";
    private static final String STRONG_MD = "strong.md";
    private static final String WEAK_MD = "weak.md";
    private static final String SMALL_MD = "small.md";
    private static final String HUGE_MD = "huge.md";
    private static final String ALL_BLOCKS = "AAAA\n\nBBBB\n\nCCCC\n\n";

    @Test
    void tokensAreLowercasedDistinctRunsOfLettersDigitsAndUnderscore() {
        assertEquals(List.of("hello", "world", "ab_9"),
            ContentScorer.tokenize("Hello, World! ab_9"));
        // pieces shorter than three characters never become tokens
        assertTrue(ContentScorer.tokenize("ab xy 12").isEmpty());
        // each token is kept only once, in first-occurrence order
        assertEquals(List.of("mod", "form"), ContentScorer.tokenize("mod form mod"));
    }

    @Test
    void missingOrNullQueryYieldsNoTokens() {
        assertTrue(ContentScorer.tokenize(null).isEmpty());
        assertTrue(ContentScorer.tokenize("").isEmpty());
    }

    @Test
    void pathScoreCountsEveryTokenContainedInThePath() {
        final String path = "docs/readme.markdown";
        // each contained token adds its name points
        assertEquals(ContentScorer.PATH_TOKEN_POINTS * 2,
            ContentScorer.pathScore(path, List.of("readme", "markdown")));
        assertEquals(0, ContentScorer.pathScore(path, List.of("other")));
    }

    @Test
    void lineScoreCountsEveryTokenPresentOnTheBestLine() {
        final String[] lines = {"first line", "the target token here", "second target"};
        final ContentScorer.LineMatch match =
            ContentScorer.bestLine(lines, List.of(QUERY, "token"));
        // only the middle line carries both tokens
        assertEquals(1, match.lineIndex());
        assertEquals(ContentScorer.LINE_TOKEN_POINTS * 2, match.score());
    }

    @Test
    void filesWithoutAnyLineHitReportNoLine() {
        final ContentScorer.LineMatch match =
            ContentScorer.bestLine(new String[] {"plain text"}, List.of("absent"));
        assertEquals(0, match.score());
        assertEquals(-1, match.lineIndex());
    }

    @Test
    void topHitsKeepOnlyTheStrongestOnesSortedDown() {
        final SearchEngine.Hit[] hits = {
            new SearchEngine.Hit(new SourceIndex.Entry(Path.of(IGNORED_DIR), WEAK_MD), 3, 0, ""),
            new SearchEngine.Hit(new SourceIndex.Entry(Path.of(IGNORED_DIR), STRONG_MD), 9, 0, ""),
            new SearchEngine.Hit(new SourceIndex.Entry(Path.of(IGNORED_DIR), "mid.md"), 6, 0, ""),
        };
        final List<SearchEngine.Hit> top = SearchEngine.keepTop(
            java.util.Arrays.asList(hits), 2);
        assertEquals(List.of(STRONG_MD, "mid.md"),
            top.stream().map(hit -> hit.file().displayPath())
                .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void excerptTakesTwoLinesAroundTheMatchAndStripsEach() {
        final String[] lines = {"", "  one  ", " two ", " three ", "four"};
        // window extends two lines each way and is clamped at the file edges
        assertEquals("one\ntwo\nthree", SnippetRenderer.excerpt(lines, 1).strip());
        assertEquals("one\ntwo\nthree\nfour", SnippetRenderer.excerpt(lines, 3).strip());
    }

    @Test
    void longLinesAreCutTo160ButTheMatchLineTo300() {
        final String longLine = "x".repeat(400);
        final String[] lines = {longLine, "  " + longLine + "  ", longLine};
        final String excerpt = SnippetRenderer.excerpt(lines, 1);
        final String[] parts = excerpt.split("\n", -1);
        assertEquals(3, parts.length);
        assertEquals(160, parts[0].length());
        assertEquals(300, parts[1].length());
        assertEquals(160, parts[2].length());
    }

    @Test
    void hitBlockCarriesPathOptionalLineAndExcerpt() {
        assertEquals("File: src/a.bsl (line 4)\nhit text\n\n",
            SnippetRenderer.block("src/a.bsl", 4, "hit text"));
        // hits without a content line show just the path; the empty snippet
        // still leaves its blank separator line
        assertEquals("File: src/a.bsl\n\n\n", SnippetRenderer.block("src/a.bsl", -1, ""));
    }

    @Test
    void renderingStopsAtTheCharacterBudget() {
        final List<String> blocks = List.of("AAAA\n\n", "BBBB\n\n", "CCCC\n\n");
        // blocks are added whole: the first that would not fit drops the rest
        assertEquals("AAAA\n\nBBBB\n\n", SnippetRenderer.render(blocks, 12));
        // a budget that fits all blocks keeps all of them, equal length still fits
        assertEquals(ALL_BLOCKS, SnippetRenderer.render(blocks, 24));
    }

    @Test
    void renderingStopsAtTheCharacterBudgetAndReportsCoverage() {
        final StringBuilder out = new StringBuilder();
        final List<String> blocks = List.of("AAAA\n\n", "BBBB\n\n", "CCCC\n\n");
        SnippetRenderer.render(blocks, 12, out, 1, 3, true, false);
        // two blocks fit into 12 characters, the third does not
        assertEquals("""
            AAAA

            BBBB

            Scanned 1 of 3 indexed files (scan cut by 8s time budget). Refine the query or the file filter.
            """, out.toString());
        out.setLength(0);
        SnippetRenderer.render(blocks, 60, out, 2, 3, false, true);
        assertEquals(ALL_BLOCKS
            + "Scanned 2 of 3 indexed files (scan cut by 120MB read budget). "
            + "Refine the query or the file filter.\n", out.toString());
        out.setLength(0);
        SnippetRenderer.render(blocks, 60, out, 3, 3, false, false);
        assertEquals(ALL_BLOCKS, out.toString());
    }

    @Test
    void timeBudgetCutsTheScanAndNamesItInTheCoverageNote(@TempDir final Path theDir)
        throws IOException {
        for(int index = 1; index <= 3; index++) {
            Files.writeString(theDir.resolve("doc" + index + ".md"), "target doc " + index,
                StandardCharsets.UTF_8);
        }
        final SourceIndex index = new SourceIndex();
        index.replace(List.of(
            new SourceIndex.Entry(theDir.resolve("doc1.md"), "doc1.md"),
            new SourceIndex.Entry(theDir.resolve("doc2.md"), "doc2.md"),
            new SourceIndex.Entry(theDir.resolve("doc3.md"), "doc3.md")));
        // the clock answers the deadline setup and the first per-file check
        // with zero, then jumps far past the deadline: only the first file
        // gets scanned
        final SearchEngine.Clock jumping = new SearchEngine.Clock() {
            private int ourCalls;

            @Override
            public long nanoTime() {
                ourCalls++;
                return ourCalls <= 2 ? 0L : ourCalls * 10_000_000_000L;
            }
        };
        final SearchEngine cutEngine = new SearchEngine(jumping, 1_000_000_000L,
            SearchEngine.DEFAULT_READ_BUDGET_BYTES, SearchEngine.DEFAULT_MAX_FILE_BYTES,
            SearchEngine.DEFAULT_MAX_RESULTS, SearchEngine.DEFAULT_RESULT_CHAR_BUDGET);
        final String result = cutEngine.search(QUERY, index.snapshot());
        assertTrue(result.contains("Scanned 1 of 2 indexed files"), result);
        assertTrue(result.contains("(scan cut by 8s time budget)"), result);
        assertTrue(result.contains("File: doc1.md (line 1)"), result);
    }

    @Test
    void readBudgetCutsTheScanAfterBytesWereConsumed(@TempDir final Path theDir)
        throws IOException {
        Files.writeString(theDir.resolve(SMALL_MD), "target small", StandardCharsets.UTF_8);
        Files.write(theDir.resolve(HUGE_MD), new byte[500]);
        final SourceIndex index = new SourceIndex();
        // small first, so its 12 bytes exhaust the one-byte budget before the
        // next file's head check cuts the scan
        index.replace(List.of(
            new SourceIndex.Entry(theDir.resolve(SMALL_MD), SMALL_MD),
            new SourceIndex.Entry(theDir.resolve(HUGE_MD), HUGE_MD)));
        final SearchEngine engine = new SearchEngine(SearchEngine.SYSTEM_CLOCK,
            SearchEngine.DEFAULT_SCAN_BUDGET_NS, 1L, 100L,
            SearchEngine.DEFAULT_MAX_RESULTS, SearchEngine.DEFAULT_RESULT_CHAR_BUDGET);
        final String result = engine.search(QUERY, index.snapshot());
        assertTrue(result.contains("(scan cut by 120MB read budget)"), result);
        // the small file was scanned and its hit survived the budget cut
        assertTrue(result.contains("File: small.md (line 1)"), result);
        assertTrue(result.contains("Scanned 1 of 2 indexed files"), result);
    }

    @Test
    void oversizedFilesAreSkippedWithoutConsumingTheBudget(@TempDir final Path theDir)
        throws IOException {
        Files.writeString(theDir.resolve(SMALL_MD), "target small", StandardCharsets.UTF_8);
        Files.write(theDir.resolve(HUGE_MD), new byte[500]);
        final SourceIndex index = new SourceIndex();
        // the huge file is listed first and must not consume the budget: the
        // one-byte budget still leaves enough for the small file to be read
        index.replace(List.of(
            new SourceIndex.Entry(theDir.resolve(HUGE_MD), HUGE_MD),
            new SourceIndex.Entry(theDir.resolve(SMALL_MD), SMALL_MD)));
        final SearchEngine engine = new SearchEngine(SearchEngine.SYSTEM_CLOCK,
            SearchEngine.DEFAULT_SCAN_BUDGET_NS, 1L, 100L,
            SearchEngine.DEFAULT_MAX_RESULTS, SearchEngine.DEFAULT_RESULT_CHAR_BUDGET);
        final String result = engine.search(QUERY, index.snapshot());
        // huge is skipped by the size cap, small is still read: its hit is
        // there and no coverage note appears
        assertTrue(result.contains("File: small.md (line 1)"), result);
        assertTrue(!result.contains("Scanned"), result);
    }

    @Test
    void searchKeepsAtMostMaxResultsAndDropsTheWeakest(@TempDir final Path theDir)
        throws IOException {
        // two query tokens: the strong line carries both, the weak one only
        // one, so their scores differ and the cap decision is deterministic
        Files.writeString(theDir.resolve(STRONG_MD), "target alpha here",
            StandardCharsets.UTF_8);
        Files.writeString(theDir.resolve(WEAK_MD), "a target", StandardCharsets.UTF_8);
        final SourceIndex index = new SourceIndex();
        index.replace(List.of(
            new SourceIndex.Entry(theDir.resolve(STRONG_MD), STRONG_MD),
            new SourceIndex.Entry(theDir.resolve(WEAK_MD), WEAK_MD)));
        final SearchEngine engine = new SearchEngine(SearchEngine.SYSTEM_CLOCK,
            SearchEngine.DEFAULT_SCAN_BUDGET_NS, SearchEngine.DEFAULT_READ_BUDGET_BYTES,
            SearchEngine.DEFAULT_MAX_FILE_BYTES, 1, SearchEngine.DEFAULT_RESULT_CHAR_BUDGET);
        final String result = engine.search("target alpha", index.snapshot());
        assertTrue(result.contains("File: strong.md"), result);
        assertTrue(!result.contains("File: weak.md"), result);
    }

    @Test
    void emptyIndexOrEmptyQueryProduceNoText(@TempDir final Path theDir) {
        final SearchEngine engine = new SearchEngine();
        assertTrue(engine.search(QUERY, List.of()).isEmpty());
        final SourceIndex index = new SourceIndex();
        index.replace(List.of(new SourceIndex.Entry(theDir.resolve("x.md"), "x.md")));
        assertTrue(engine.search("ab", index.snapshot()).isEmpty());
    }
}
