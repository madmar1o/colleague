package com.mad.edt.colleague.edit;

import java.util.List;

/**
 * Applies edit blocks to file text one after another: every block searches in
 * the text produced by the previous one. Rules the model must respect: the
 * searched fragment must occur exactly once in the current text, blocks that
 * change nothing are skipped, and an empty fragment replaces the whole file.
 * Search and replacement snippets follow the file's own line endings, so a
 * plain-LF snippet finds its place in a CRLF file and vice versa.
 */
public final class TextEditEngine {

    /** Files longer than this many lines are refused outright. */
    static final int MAX_LINES = 20_000;

    /**
     * Either the new full text or a refusal naming the offending block.
     */
    public static final class Result {

        private final String text;
        private final int appliedBlocks;
        private final boolean changed;
        private final String error;

        private Result(final String theText, final int theAppliedBlocks, final boolean theChanged,
            final String theError) {
            text = theText;
            appliedBlocks = theAppliedBlocks;
            changed = theChanged;
            error = theError;
        }

        static Result ok(final String theText, final int theAppliedBlocks, final boolean theChanged) {
            return new Result(theText, theAppliedBlocks, theChanged, null);
        }

        static Result refused(final String theError) {
            return new Result("", 0, false, theError);
        }

        /**
         * @return {@code true} when every block landed and {@link #getText()} is final
         */
        public boolean isOk() {
            return error == null;
        }

        /**
         * @return {@code true} when the produced text differs from the original,
         *         {@code false} for refusals and for scripts that changed nothing
         */
        public boolean isChanged() {
            return changed;
        }

        /**
         * @return the new full file text, empty on refusal
         */
        public String getText() {
            return text;
        }

        /**
         * @return how many blocks actually changed the text
         */
        public int getAppliedBlocks() {
            return appliedBlocks;
        }

        /**
         * @return refusal message naming the block and the reason, {@code null} on success
         */
        public String getError() {
            return error;
        }
    }

    /**
     * Applies the blocks in order over the given text.
     *
     * @param theOriginal current file text, {@code null} means empty
     * @param theBlocks requested changes, each searching in the previous result
     * @return the new text when every block landed, otherwise a refusal
     */
    public Result apply(final String theOriginal, final List<EditBlock> theBlocks) {
        String original = theOriginal == null ? "" : theOriginal;
        if (countLines(original) > MAX_LINES) {
            return Result.refused("File is too large for line-based editing: more than "
                + MAX_LINES + " lines. Ask a human to edit it in the editor.");
        }
        String current = original;
        int applied = 0;
        for (int index = 0; index < theBlocks.size(); index++) {
            EditBlock block = theBlocks.get(index);
            String search = alignToLineEndings(block.getSearchText(), lineEndingOf(current));
            String replace = alignToLineEndings(block.getReplaceText(), lineEndingOf(current));
            if (search.isEmpty()) {
                if (!replace.equals(current)) {
                    current = replace;
                    applied++;
                }
                continue;
            }
            if (search.equals(replace)) {
                continue;
            }
            int first = current.indexOf(search);
            if (first < 0) {
                return Result.refused("Edit refused at block " + (index + 1)
                    + ": the fragment was not found in the file. Check that it is copied"
                    + " exactly from the current file content.");
            }
            if (current.indexOf(search, first + 1) >= 0) {
                return Result.refused("Edit refused at block " + (index + 1)
                    + ": the fragment was found more than once. Include more surrounding"
                    + " lines so the fragment is unique.");
            }
            current = current.substring(0, first) + replace + current.substring(first + search.length());
            applied++;
        }
        return Result.ok(current, applied, !current.equals(original));
    }

    private static String lineEndingOf(final String theText) {
        return theText.contains("\r\n") ? "\r\n" : "\n";
    }

    private static String alignToLineEndings(final String theSnippet, final String theLineEnding) {
        return theSnippet.replace("\r\n", "\n").replace("\n", theLineEnding);
    }

    private static int countLines(final String theText) {
        int count = 1;
        for (int index = theText.indexOf('\n'); index >= 0; index = theText.indexOf('\n', index + 1)) {
            count++;
        }
        return count;
    }
}
