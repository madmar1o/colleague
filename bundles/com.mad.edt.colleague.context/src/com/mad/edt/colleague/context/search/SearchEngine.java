package com.mad.edt.colleague.context.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Per-query content scan over an index snapshot: files are pre-sorted by path
 * score so path matches survive even when budgets cut the scan, each read is
 * accounted against byte and time budgets, and the strongest hits are
 * rendered as short snippets with paths and line numbers. Budgets and the
 * clock are constructor-injected so cuts can be pinned by tests.
 */
public final class SearchEngine {

    /** Monotonic time source the scan checks against its deadline. */
    public interface Clock {

        /** @return current monotonic time in nanoseconds */
        long nanoTime();
    }

    /** Production clock. */
    public static final Clock SYSTEM_CLOCK = System::nanoTime;

    /** Default per-query wall budget for the content scan, nanoseconds. */
    public static final long DEFAULT_SCAN_BUDGET_NS = 8_000_000_000L;

    /** Default per-query budget of read file bytes. */
    public static final long DEFAULT_READ_BUDGET_BYTES = 120L * 1024L * 1024L;

    /** Files larger than this are skipped and cost no read budget. */
    public static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    /** How many strongest hits the scan keeps. */
    public static final int DEFAULT_MAX_RESULTS = 6;

    /** Default total characters of all rendered hit blocks. */
    public static final int DEFAULT_RESULT_CHAR_BUDGET = 3_500;

    /** One scored search hit. */
    public static final class Hit {

        private final SourceIndex.Entry ourFile;
        private final int ourScore;
        private final int ourLine;
        private final String ourExcerpt;

        /**
         * @param theFile indexed file of the hit
         * @param theScore summed path and line token points
         * @param theLine one-based best content line, {@code -1} when no line
         *            matched
         * @param theExcerpt snippet around the best line, empty when none
         */
        public Hit(final SourceIndex.Entry theFile, final int theScore, final int theLine,
            final String theExcerpt) {
            ourFile = theFile;
            ourScore = theScore;
            ourLine = theLine;
            ourExcerpt = theExcerpt;
        }

        /** @return indexed file of this hit */
        public SourceIndex.Entry file() {
            return ourFile;
        }

        /** @return hit strength */
        public int score() {
            return ourScore;
        }

        /** @return one-based best line, {@code -1} when no line matched */
        public int line() {
            return ourLine;
        }

        /** @return snippet around the best line */
        public String excerpt() {
            return ourExcerpt;
        }
    }

    private final Clock clock;
    private final long scanBudgetNs;
    private final long readBudgetBytes;
    private final long maxFileBytes;
    private final int maxResults;
    private final int resultCharBudget;

    /** Production engine with default budgets and the system clock. */
    public SearchEngine() {
        this(SYSTEM_CLOCK, DEFAULT_SCAN_BUDGET_NS, DEFAULT_READ_BUDGET_BYTES,
            DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_RESULTS, DEFAULT_RESULT_CHAR_BUDGET);
    }

    /**
     * @param theClock monotonic time source
     * @param theScanBudgetNs wall budget of one scan, nanoseconds
     * @param theReadBudgetBytes budget of bytes one scan may read
     * @param theMaxFileBytes files above this size are skipped
     * @param theMaxResults number of strongest hits kept
     * @param theResultCharBudget total characters of rendered hit blocks
     */
    public SearchEngine(final Clock theClock, final long theScanBudgetNs,
        final long theReadBudgetBytes, final long theMaxFileBytes, final int theMaxResults,
        final int theResultCharBudget) {
        clock = theClock;
        scanBudgetNs = theScanBudgetNs;
        readBudgetBytes = theReadBudgetBytes;
        maxFileBytes = theMaxFileBytes;
        maxResults = theMaxResults;
        resultCharBudget = theResultCharBudget;
    }

    /**
     * Scans the given index snapshot for the query.
     *
     * @param theQuery free-form query text
     * @param theIndex index snapshot from {@link SourceIndex#snapshot()}
     * @return formatted snippets for the model, empty string when nothing
     *         matched; includes a coverage note when budgets cut the scan
     */
    public String search(final String theQuery, final List<SourceIndex.Entry> theIndex) {
        final List<String> tokens = ContentScorer.tokenize(theQuery);
        if(tokens.isEmpty() || theIndex.isEmpty()) {
            return "";
        }

        // Pass 1: score every path cheaply, before any file content is read,
        // so path matches survive even when the read budget cuts the scan.
        final int[] nameScores = new int[theIndex.size()];
        final Integer[] order = new Integer[theIndex.size()];
        for(int index = 0; index < theIndex.size(); index++) {
            nameScores[index] = ContentScorer.pathScore(theIndex.get(index).lowerPath(), tokens);
            order[index] = Integer.valueOf(index);
        }
        java.util.Arrays.sort(order, (left, right) ->
            Integer.compare(nameScores[right.intValue()], nameScores[left.intValue()]));

        final long deadline = clock.nanoTime() + scanBudgetNs;
        long readBytes = 0L;
        int scannedFiles = 0;
        int visitedFiles = 0;
        boolean cutByTime = false;
        boolean cutByBytes = false;

        final Comparator<Hit> byScore = Comparator.comparingInt(hit -> Integer.valueOf(hit.score()));
        final PriorityQueue<Hit> top = new PriorityQueue<>(byScore);

        for(final Integer position : order) {
            visitedFiles++;
            final int index = position.intValue();
            final SourceIndex.Entry entry = theIndex.get(index);
            final int nameScore = nameScores[index];
            if(clock.nanoTime() > deadline) {
                cutByTime = true;
                break;
            }
            if(readBytes >= readBudgetBytes) {
                cutByBytes = true;
                break;
            }
            try {
                final long size = Files.size(entry.location());
                if(size > maxFileBytes) {
                    continue;
                }
                readBytes += size;
                final String content = new String(Files.readAllBytes(entry.location()),
                    StandardCharsets.UTF_8);
                final String[] lines = content.split("\r?\n", -1);
                final ContentScorer.LineMatch match = ContentScorer.bestLine(lines, tokens);
                if(match.score() == 0 && nameScore == 0) {
                    continue;
                }
                scannedFiles++;
                final int score = nameScore + match.score();
                final String excerpt = match.lineIndex() < 0 ? ""
                    : SnippetRenderer.excerpt(lines, match.lineIndex());
                top.offer(new Hit(entry, score, match.lineIndex() + 1, excerpt));
                if(top.size() > maxResults) {
                    top.poll();
                }
            } catch (final IOException ex) {
                // unreadable file, skip
            }
        }

        final List<Hit> hits = keepTop(top, maxResults);
        if(hits.isEmpty()) {
            return "";
        }
        final List<String> blocks = new ArrayList<>(hits.size());
        for(final Hit hit : hits) {
            blocks.add(SnippetRenderer.block(hit.file().displayPath(), hit.line(),
                hit.excerpt()));
        }
        final StringBuilder result = new StringBuilder();
        SnippetRenderer.render(blocks, resultCharBudget, result, scannedFiles, visitedFiles,
            cutByTime, cutByBytes);
        return result.toString();
    }

    /**
     * Keeps the strongest hits, strongest first. Weaker hits drop out as the
     * collection overflows the cap.
     *
     * @param theHits hits seen so far
     * @param theMaxResults cap on kept hits
     * @return kept hits sorted by score descending
     */
    static List<Hit> keepTop(final Iterable<Hit> theHits, final int theMaxResults) {
        final Comparator<Hit> byScore = Comparator.comparingInt(hit -> Integer.valueOf(hit.score()));
        final PriorityQueue<Hit> top = new PriorityQueue<>(byScore);
        for(final Hit hit : theHits) {
            top.offer(hit);
            if(top.size() > theMaxResults) {
                top.poll();
            }
        }
        final List<Hit> hits = new ArrayList<>(top);
        hits.sort(byScore.reversed());
        return hits;
    }
}
