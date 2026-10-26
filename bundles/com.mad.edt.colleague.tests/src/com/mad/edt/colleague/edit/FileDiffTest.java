package com.mad.edt.colleague.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins how a before/after pair is rendered as a unified diff for the human
 * confirmation dialog: changed lines marked with minus and plus, at most
 * three context lines around each change, far-apart changes split into
 * separate hunks, an unchanged file rendering to nothing, and an oversized
 * diff cut short with a visible note.
 */
class FileDiffTest {

    @Test
    void unchangedFileRendersNoDiff() {
        assertEquals("", FileDiff.render("Module.bsl", "one\ntwo\n", "one\ntwo\n"));
    }

    @Test
    void replacedLineIsShownAsMinusPlusPair() {
        String diff = FileDiff.render("Module.bsl", "one\ntwo\nthree\n", "one\nTWO\nthree\n");
        assertTrue(diff.contains("-two"), diff);
        assertTrue(diff.contains("+TWO"), diff);
    }

    @Test
    void fileNameAppearsInTheHeader() {
        String diff = FileDiff.render("Module.bsl", "a\n", "b\n");
        assertTrue(diff.contains("Module.bsl"), diff);
    }

    @Test
    void upToThreeContextLinesAroundAChange() {
        StringBuilder before = new StringBuilder();
        for (int line = 1; line <= 10; line++) {
            before.append("line ").append(line).append('\n');
        }
        String after = before.toString().replace("line 5", "LINE FIVE");
        String diff = FileDiff.render("f.bsl", before.toString(), after);
        assertTrue(diff.contains(" line 2\n"), diff);
        assertTrue(diff.contains(" line 4\n"), diff);
        assertTrue(diff.contains(" line 6\n"), diff);
        assertTrue(diff.contains(" line 8\n"), diff);
        assertTrue(!diff.contains(" line 1\n"), diff);
        assertTrue(!diff.contains("line 9"), diff);
        assertEquals(1, countHunks(diff), diff);
    }

    @Test
    void farApartChangesBecomeSeparateHunks() {
        StringBuilder before = new StringBuilder();
        for (int line = 1; line <= 30; line++) {
            before.append("line ").append(line).append('\n');
        }
        String after = before.toString()
            .replace("line 2", "CHANGED TWO")
            .replace("line 25", "CHANGED TWENTY FIVE");
        String diff = FileDiff.render("f.bsl", before.toString(), after);
        assertEquals(2, countHunks(diff), diff);
    }

    @Test
    void insertionShowsHunkWithoutMinusLines() {
        String diff = FileDiff.render("f.bsl", "one\nthree\n", "one\ntwo\nthree\n");
        assertTrue(diff.contains("+two"), diff);
        assertTrue(!diff.contains("-one"), diff);
        assertTrue(!diff.contains("-three"), diff);
    }

    @Test
    void oversizedDiffIsCutShortWithNote() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int line = 0; line < 2_000; line++) {
            before.append("old ").append(line).append('\n');
            after.append("new ").append(line).append('\n');
        }
        String diff = FileDiff.render("f.bsl", before.toString(), after.toString());
        assertTrue(diff.contains("truncated"), diff);
        assertTrue(diff.split("\n").length <= FileDiff.MAX_DIFF_LINES + 10, diff);
    }

    private static int countHunks(final String theDiff) {
        int count = 0;
        for (String line : theDiff.split("\n")) {
            if (line.startsWith("@@")) {
                count++;
            }
        }
        return count;
    }
}
