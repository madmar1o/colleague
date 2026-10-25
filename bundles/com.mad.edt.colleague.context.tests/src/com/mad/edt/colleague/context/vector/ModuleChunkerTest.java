package com.mad.edt.colleague.context.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.context.vector.ModuleChunker.Chunk;

/**
 * Pins the windowing rules the vector index relies on: the production 55/10
 * window repeats ten lines at the border so a construct cut by one window is
 * whole inside a neighbour, trailing blank lines never start a window, text
 * keeps exact line content, and bad window sizes are refused at construction.
 */
class ModuleChunkerTest {

    /** the sizes the index actually runs with */
    private static final int CHUNK_LINES = 55;
    private static final int OVERLAP_LINES = 10;

    private static ModuleChunker production() {
        return new ModuleChunker(CHUNK_LINES, OVERLAP_LINES);
    }

    /** file of the given line count, each line numbered from one */
    private static String numberedFile(final int theLineCount) {
        return IntStream.rangeClosed(1, theLineCount)
            .mapToObj(n -> "line" + n)
            .collect(Collectors.joining("\n"));
    }

    @Test
    void firstWindowTakesFiftyFiveLines() {
        final List<Chunk> chunks = production().split(numberedFile(120));
        assertEquals(1, chunks.get(0).getStartLine());
        assertEquals(CHUNK_LINES, chunks.get(0).getEndLine());
        assertEquals("line1\nline2", chunks.get(0).getText().substring(0, 11));
    }

    @Test
    void nextWindowRepeatsTenLinesOfThePreviousOne() {
        final List<Chunk> chunks = production().split(numberedFile(120));
        // window one covers 1..55, window two resumes at 46 with ten shared lines
        assertEquals(46, chunks.get(1).getStartLine());
        assertEquals(100, chunks.get(1).getEndLine());
        assertEquals(91, chunks.get(2).getStartLine());
        assertEquals(120, chunks.get(2).getEndLine());
        assertEquals(3, chunks.size());
    }

    @Test
    void windowsEndFlushWithTheLastContentLine() {
        // 130 lines: 1..55, 46..100, 91..130 (40 lines in the last window)
        final List<Chunk> chunks = production().split(numberedFile(130));
        assertEquals(1, chunks.get(0).getStartLine());
        assertEquals(100, chunks.get(1).getEndLine());
        assertEquals(130, chunks.get(chunks.size() - 1).getEndLine());
        // text of the last window really ends at line 130
        final Chunk last = chunks.get(chunks.size() - 1);
        assertEquals("line130", last.getText().substring(last.getText().lastIndexOf('\n') + 1));
    }

    @Test
    void shortFileStaysOneWindow() {
        final List<Chunk> chunks = production().split(numberedFile(30));
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).getStartLine());
        assertEquals(30, chunks.get(0).getEndLine());
    }

    @Test
    void trailingBlankLinesAndCarriageReturnsDoNotStartWindows() {
        final String text = numberedFile(55) + "\r\n   \n\t\n";
        final List<Chunk> chunks = production().split(text);
        // the blank tail does not push a second window into existence, and
        // the 56th raw line (blank) is cut away
        assertEquals(1, chunks.size());
        assertEquals(55, chunks.get(0).getEndLine());
    }

    @Test
    void blankAndNullInputProduceNoWindows() {
        assertTrue(production().split("").isEmpty());
        assertTrue(production().split("   \n\n").isEmpty());
        assertTrue(production().split(null).isEmpty());
    }

    @Test
    void singleLineFileBecomesOneWindow() {
        final List<Chunk> chunks = production().split("procedure");
        assertEquals(1, chunks.size());
        assertEquals("procedure", chunks.get(0).getText());
        assertEquals(1, chunks.get(0).getStartLine());
        assertEquals(1, chunks.get(0).getEndLine());
    }

    @Test
    void badWindowSizesRefusedAtConstruction() {
        // overlap must stay smaller than the window and never negative
        assertThrows(IllegalArgumentException.class, () -> new ModuleChunker(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModuleChunker(55, 55));
        assertThrows(IllegalArgumentException.class, () -> new ModuleChunker(55, -1));
    }

    @Test
    void exactLineContentSurvivesTheWindow() {
        final String text = "Procedure name\n\tIf x Then\n\t\tDo();\n\tEndIf;";
        final List<Chunk> chunks = production().split(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0).getText());
    }
}
