package com.mad.edt.colleague.context.search;

import java.util.List;

/**
 * Text shaping of search results: the snippet window around a matched line
 * with per-line length caps, the model-facing block format with path and line
 * number, fitting blocks into a character budget, and the coverage note that
 * tells the model when scan budgets cut its answer short.
 */
public final class SnippetRenderer {

    /** Cap of a non-matching snippet line in characters. */
    private static final int CONTEXT_LINE_CHARS = 160;

    /** Cap of the matching snippet line in characters. */
    private static final int MATCH_LINE_CHARS = 300;

    /** Snippet reaches this many lines around the match. */
    private static final int SNIPPET_CONTEXT_LINES = 2;

    private SnippetRenderer() {
    }

    /**
     * Extracts the snippet around the match: surrounding lines with each
     * stripped, long lines cut - the match line gets the wider cap.
     *
     * @param theLines file split into lines
     * @param theLineIndex zero-based position of the match
     * @return snippet text
     */
    public static String excerpt(final String[] theLines, final int theLineIndex) {
        final int from = Math.max(0, theLineIndex - SNIPPET_CONTEXT_LINES);
        final int to = Math.min(theLines.length - 1, theLineIndex + SNIPPET_CONTEXT_LINES);
        final StringBuilder excerpt = new StringBuilder();
        for(int index = from; index <= to; index++) {
            final String line = theLines[index].strip();
            if(line.length() > CONTEXT_LINE_CHARS) {
                excerpt.append(line, 0, Math.min(
                    theLineIndex == index ? MATCH_LINE_CHARS : CONTEXT_LINE_CHARS,
                    line.length())).append('\n');
            } else {
                excerpt.append(line).append('\n');
            }
        }
        return excerpt.toString().strip();
    }

    /**
     * Formats one hit for the model: path, optional line and snippet.
     *
     * @param theDisplayPath project-relative file path
     * @param theLine one-based best line, {@code -1} when no line matched
     * @param theExcerpt snippet text
     * @return one hit block ending with a blank line
     */
    public static String block(final String theDisplayPath, final int theLine,
        final String theExcerpt) {
        return "File: " + theDisplayPath
            + (theLine > 0 ? " (line " + theLine + ")" : "")
            + "\n" + theExcerpt + "\n\n";
    }

    /**
     * Appends hit blocks in order while they fit the character budget; the
     * first block that would not fit drops the rest.
     *
     * @param theBlocks rendered hit blocks, strongest first
     * @param theCharBudget total characters budget
     * @param theResult receives the fitted text
     * @param theScanned files actually content-scanned
     * @param theVisited files the scan reached before cutting
     * @param theCutByTime set when the time budget ended the scan
     * @param theCutByBytes set when the read budget ended the scan
     */
    public static void render(final List<String> theBlocks, final int theCharBudget,
        final StringBuilder theResult, final int theScanned, final int theVisited,
        final boolean theCutByTime, final boolean theCutByBytes) {
        for(final String block : theBlocks) {
            if(theResult.length() + block.length() > theCharBudget) {
                break;
            }
            theResult.append(block);
        }
        if(theCutByTime || theCutByBytes) {
            theResult.append("Scanned ").append(theScanned).append(" of ")
                .append(theVisited).append(" indexed files");
            if(theCutByTime) {
                theResult.append(" (scan cut by 8s time budget)");
            }
            if(theCutByBytes) {
                theResult.append(" (scan cut by 120MB read budget)");
            }
            theResult.append(". Refine the query or the file filter.\n");
        }
    }

    /** @return fitted hit blocks text without any coverage note */
    public static String render(final List<String> theBlocks, final int theCharBudget) {
        final StringBuilder result = new StringBuilder();
        for(final String block : theBlocks) {
            if(result.length() + block.length() > theCharBudget) {
                break;
            }
            result.append(block);
        }
        return result.toString();
    }
}
