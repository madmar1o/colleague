package com.mad.edt.colleague.context.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits module source text into overlapping line windows suitable for
 * embedding. Windows are sized in lines, not characters, so a chunk always
 * contains whole statements' worth of code; consecutive chunks share a few
 * lines so a construct cut at a window border is still fully present in one
 * of the two neighbours.
 */
public final class ModuleChunker {

    /**
     * One window of source lines.
     */
    public static final class Chunk {

        private final int ourStartLine;
        private final int ourEndLine;
        private final String ourText;

        Chunk(final int theStartLine, final int theEndLine, final String theText) {
            ourStartLine = theStartLine;
            ourEndLine = theEndLine;
            ourText = theText;
        }

        /**
         * @return one-based first line of the window
         */
        public int getStartLine() {
            return ourStartLine;
        }

        /**
         * @return one-based last line of the window, inclusive
         */
        public int getEndLine() {
            return ourEndLine;
        }

        /**
         * @return exact source lines of the window joined by newlines
         */
        public String getText() {
            return ourText;
        }
    }

    private final int ourChunkLines;
    private final int ourOverlapLines;

    /**
     * @param theChunkLines lines per window
     * @param theOverlapLines lines repeated in the next window
     */
    public ModuleChunker(final int theChunkLines, final int theOverlapLines) {
        if(theChunkLines <= 1 || theOverlapLines < 0 || theOverlapLines >= theChunkLines) {
            throw new IllegalArgumentException("bad window parameters: " + theChunkLines
                + '/' + theOverlapLines);
        }
        ourChunkLines = theChunkLines;
        ourOverlapLines = theOverlapLines;
    }

    /**
     * @param theText whole file text
     * @return windows in ascending line order, empty for blank input
     */
    public List<Chunk> split(final String theText) {
        final List<Chunk> chunks = new ArrayList<>();
        if(theText == null || theText.isBlank()) {
            return chunks;
        }
        final String[] lines = theText.split("\r?\n", -1);
        int last = lines.length;
        while(last > 0 && lines[last - 1].isBlank()) {
            last--;
        }
        int start = 0;
        while(start < last) {
            final int end = Math.min(start + ourChunkLines, last);
            final StringBuilder text = new StringBuilder();
            for(int line = start; line < end; line++) {
                if(line > start) {
                    text.append('\n');
                }
                text.append(lines[line]);
            }
            chunks.add(new Chunk(start + 1, end, text.toString()));
            if(end == last) {
                break;
            }
            start = end - ourOverlapLines;
        }
        return chunks;
    }
}
