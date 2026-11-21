package com.mad.edt.colleague.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a before/after pair as a compact unified diff so a human can see
 * exactly what an edit script would change before approving it. Changed
 * lines carry minus and plus prefixes, unchanged lines nearby are shown for
 * context, changes far apart become separate hunks, and a diff too long to
 * review is cut short with a visible note.
 */
public final class FileDiff {

    /** Upper bound on the number of diff lines shown to a human. */
    static final int MAX_DIFF_LINES = 240;

    /** Unchanged lines shown on each side of a changed region. */
    private static final int CONTEXT_LINES = 3;

    /** Line-pair budget for the exact match computation; beyond it the changed region is shown as one wholesale replacement. */
    private static final long MAX_EXACT_PAIRS = 4_000_000L;

    private FileDiff() {
    }

    /**
     * @param theFileName file name to put into the diff header
     * @param theBefore text before the edit, {@code null} means empty
     * @param theAfter text after the edit, {@code null} means empty
     * @return unified diff text, empty string when both texts match line for line
     */
    public static String render(final String theFileName, final String theBefore,
        final String theAfter) {
        List<String> oldLines = toLines(theBefore == null ? "" : theBefore);
        List<String> newLines = toLines(theAfter == null ? "" : theAfter);
        List<MarkedLine> marks = mark(oldLines, newLines);
        List<int[]> groups = changeGroups(marks);
        if (groups.isEmpty()) {
            return "";
        }
        return renderHunks(theFileName, marks, groups);
    }

    private static List<String> toLines(final String theText) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start <= theText.length()) {
            int end = theText.indexOf('\n', start);
            if (end < 0) {
                if (start < theText.length()) {
                    lines.add(stripCr(theText.substring(start)));
                }
                break;
            }
            lines.add(stripCr(theText.substring(start, end)));
            start = end + 1;
        }
        return lines;
    }

    private static String stripCr(final String theLine) {
        return theLine.endsWith("\r") ? theLine.substring(0, theLine.length() - 1) : theLine;
    }

    private static final class MarkedLine {

        private final char tag;
        private final String text;

        private MarkedLine(final char theTag, final String theText) {
            tag = theTag;
            text = theText;
        }
    }

    private static List<MarkedLine> mark(final List<String> theOldLines,
        final List<String> theNewLines) {
        int prefix = 0;
        while (prefix < theOldLines.size() && prefix < theNewLines.size()
            && theOldLines.get(prefix).equals(theNewLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < theOldLines.size() - prefix && suffix < theNewLines.size() - prefix
            && theOldLines.get(theOldLines.size() - 1 - suffix)
                .equals(theNewLines.get(theNewLines.size() - 1 - suffix))) {
            suffix++;
        }
        List<MarkedLine> marks = new ArrayList<>();
        for (int index = 0; index < prefix; index++) {
            marks.add(new MarkedLine(' ', theOldLines.get(index)));
        }
        List<int[]> middleOps = exactOrCoarse(
            theOldLines.subList(prefix, theOldLines.size() - suffix),
            theNewLines.subList(prefix, theNewLines.size() - suffix));
        int oldIndex = prefix;
        int newIndex = prefix;
        for (int[] op : middleOps) {
            char tag = (char) op[0];
            if (tag == '-') {
                marks.add(new MarkedLine('-', theOldLines.get(oldIndex++)));
            } else if (tag == '+') {
                marks.add(new MarkedLine('+', theNewLines.get(newIndex++)));
            } else {
                marks.add(new MarkedLine(' ', theOldLines.get(oldIndex++)));
                newIndex++;
            }
        }
        for (int index = 0; index < suffix; index++) {
            marks.add(new MarkedLine(' ', theOldLines.get(theOldLines.size() - suffix + index)));
        }
        return marks;
    }

    private static List<int[]> exactOrCoarse(final List<String> theOldMiddle,
        final List<String> theNewMiddle) {
        if ((long) theOldMiddle.size() * theNewMiddle.size() > MAX_EXACT_PAIRS) {
            List<int[]> ops = new ArrayList<>();
            for (int index = 0; index < theOldMiddle.size(); index++) {
                ops.add(new int[] { '-', 1 });
            }
            for (int index = 0; index < theNewMiddle.size(); index++) {
                ops.add(new int[] { '+', 1 });
            }
            return ops;
        }
        return exactOps(theOldMiddle, theNewMiddle);
    }

    private static List<int[]> exactOps(final List<String> theOldMiddle,
        final List<String> theNewMiddle) {
        int oldSize = theOldMiddle.size();
        int newSize = theNewMiddle.size();
        int[][] common = new int[oldSize + 1][newSize + 1];
        for (int oldIndex = oldSize - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newSize - 1; newIndex >= 0; newIndex--) {
                common[oldIndex][newIndex] =
                    theOldMiddle.get(oldIndex).equals(theNewMiddle.get(newIndex))
                    ? common[oldIndex + 1][newIndex + 1] + 1
                    : Math.max(common[oldIndex + 1][newIndex], common[oldIndex][newIndex + 1]);
            }
        }
        List<int[]> ops = new ArrayList<>();
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < oldSize && newIndex < newSize) {
            if (theOldMiddle.get(oldIndex).equals(theNewMiddle.get(newIndex))) {
                ops.add(new int[] { ' ', 1 });
                oldIndex++;
                newIndex++;
            } else if (common[oldIndex + 1][newIndex] >= common[oldIndex][newIndex + 1]) {
                ops.add(new int[] { '-', 1 });
                oldIndex++;
            } else {
                ops.add(new int[] { '+', 1 });
                newIndex++;
            }
        }
        while (oldIndex < oldSize) {
            ops.add(new int[] { '-', 1 });
            oldIndex++;
        }
        while (newIndex < newSize) {
            ops.add(new int[] { '+', 1 });
            newIndex++;
        }
        return ops;
    }

    private static List<int[]> changeGroups(final List<MarkedLine> theMarks) {
        List<int[]> groups = new ArrayList<>();
        int lastChange = -2 * CONTEXT_LINES;
        for (int index = 0; index < theMarks.size(); index++) {
            if (theMarks.get(index).tag == ' ') {
                continue;
            }
            if (groups.isEmpty() || index - lastChange > 2 * CONTEXT_LINES) {
                groups.add(new int[] { Math.max(0, index - CONTEXT_LINES), index });
            } else {
                groups.get(groups.size() - 1)[1] = index;
            }
            lastChange = index;
        }
        for (int[] group : groups) {
            group[1] = Math.min(group[1] + CONTEXT_LINES, theMarks.size() - 1);
        }
        return groups;
    }

    private static String renderHunks(final String theFileName, final List<MarkedLine> theMarks,
        final List<int[]> theGroups) {
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(theFileName).append('\n');
        out.append("+++ b/").append(theFileName).append('\n');
        int shownLines = 2;
        for (int[] group : theGroups) {
            int bodyLines = appendHunk(out, theMarks, group, shownLines);
            if (bodyLines < 0) {
                return out.toString();
            }
            shownLines += bodyLines + 1;
        }
        return out.toString();
    }

    /**
     * Appends one hunk with its header; when the hunk would cross the answer
     * budget, appends the truncation note instead.
     *
     * @param theOut diff being built
     * @param theMarks all marked lines
     * @param theGroup inclusive index range of the hunk
     * @param theShownLines lines already shown
     * @return lines of the hunk body, -1 when the diff was truncated here
     */
    private static int appendHunk(StringBuilder theOut, List<MarkedLine> theMarks,
        int[] theGroup, int theShownLines) {
        int[] starts = hunkStarts(theMarks, theGroup[0]);
        int[] counts = new int[2];
        StringBuilder body = hunkBody(theMarks, theGroup[0], theGroup[1], counts);
        int bodyLines = body.toString().split("\n", -1).length - 1;
        if (theShownLines + bodyLines + 1 > MAX_DIFF_LINES) {
            theOut.append("... diff truncated, more changes are hidden beyond ")
                .append(MAX_DIFF_LINES).append(" lines ...\n");
            return -1;
        }
        theOut.append("@@ -").append(starts[0]).append(',').append(counts[0])
            .append(" +").append(starts[1]).append(',').append(counts[1])
            .append(" @@\n").append(body);
        return bodyLines;
    }

    /**
     * @param theMarks all marked lines
     * @param theFrom first hunk index
     * @return start line numbers in the old and the new file
     */
    private static int[] hunkStarts(List<MarkedLine> theMarks, int theFrom) {
        int oldStart = 1;
        int newStart = 1;
        for (int index = 0; index < theFrom; index++) {
            if (theMarks.get(index).tag != '+') {
                oldStart++;
            }
            if (theMarks.get(index).tag != '-') {
                newStart++;
            }
        }
        return new int[] { oldStart, newStart };
    }

    /**
     * @param theMarks all marked lines
     * @param theFrom first hunk index, inclusive
     * @param theTo last hunk index, inclusive
     * @param theCounts output: old and new line counts of the hunk
     * @return the hunk body text
     */
    private static StringBuilder hunkBody(List<MarkedLine> theMarks, int theFrom, int theTo,
        int[] theCounts) {
        StringBuilder body = new StringBuilder();
        for (int index = theFrom; index <= theTo; index++) {
            MarkedLine mark = theMarks.get(index);
            body.append(mark.tag == ' ' ? " " : mark.tag).append(mark.text).append('\n');
            if (mark.tag != '+') {
                theCounts[0]++;
            }
            if (mark.tag != '-') {
                theCounts[1]++;
            }
        }
        return body;
    }
}
