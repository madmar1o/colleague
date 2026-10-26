package com.mad.edt.colleague.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins how the engine lands requested text changes: unique-match rule,
 * refusal that names the offending block, blocks chaining over the produced
 * text, whole-file replacement through an empty search, skipped no-op blocks
 * and the line-ending tolerance that lets a plain-LF snippet find its place
 * in a CRLF file.
 */
class TextEditEngineTest {

    private final TextEditEngine engine = new TextEditEngine();

    @Test
    void singleUniqueMatchIsReplaced() {
        TextEditEngine.Result result =
            engine.apply("Proc A\nEndProc\n", List.of(new EditBlock("Proc A", "Proc B")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("Proc B\nEndProc\n", result.getText());
        assertEquals(1, result.getAppliedBlocks());
    }

    @Test
    void blockThatFindsNothingRefusesAndNamesItsPlace() {
        TextEditEngine.Result result = engine.apply("Proc A\nEndProc\n",
            List.of(new EditBlock("Proc A", "Proc B"), new EditBlock("Module.bsl", "x")));
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("block 2"), result.getError());
        assertTrue(result.getError().contains("not found"), result.getError());
    }

    @Test
    void ambiguousMatchRefusesAskingForALongerFragment() {
        TextEditEngine.Result result = engine.apply("x\na();\nx\na();\n",
            List.of(new EditBlock("a();", "b();")));
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("more than once"), result.getError());
    }

    @Test
    void laterBlocksSearchInTheTextEarlierBlocksProduced() {
        TextEditEngine.Result result = engine.apply("one two three",
            List.of(new EditBlock("two", "2"), new EditBlock("2 three", "2 3")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("one 2 3", result.getText());
        assertEquals(2, result.getAppliedBlocks());
    }

    @Test
    void emptySearchReplacesTheWholeFile() {
        TextEditEngine.Result result =
            engine.apply("old contents", List.of(new EditBlock("", "brand new")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("brand new", result.getText());
    }

    @Test
    void blockReplacingTextWithItselfIsSkippedNotFailed() {
        TextEditEngine.Result result =
            engine.apply("same", List.of(new EditBlock("same", "same")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("same", result.getText());
        assertEquals(0, result.getAppliedBlocks());
    }

    @Test
    void allBlocksNoChangeStillRefusesSoTheModelRepairsTheScript() {
        TextEditEngine.Result result =
            engine.apply("same", List.of(new EditBlock("same", "same")));
        assertFalse(result.isChanged());
    }

    @Test
    void plainLfSnippetFindsItsPlaceInACrlfFile() {
        TextEditEngine.Result result =
            engine.apply("line one\r\nline two\r\n", List.of(new EditBlock("line two", "line 2")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("line one\r\nline 2\r\n", result.getText());
    }

    @Test
    void replacementSnippetsFollowTheFilesOwnLineEndings() {
        TextEditEngine.Result result = engine.apply("a\r\nb\r\n",
            List.of(new EditBlock("b", "b2\nb3")));
        assertTrue(result.isOk(), result.getError());
        assertEquals("a\r\nb2\r\nb3\r\n", result.getText());
    }

    @Test
    void overlongFileIsRefusedWithALinearMessage() {
        final StringBuilder huge = new StringBuilder();
        for(int line = 0; line < 20_001; line++) {
            huge.append("line ").append(line).append('\n');
        }
        TextEditEngine.Result result =
            engine.apply(huge.toString(), List.of(new EditBlock("line 1", "L1")));
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("too large"), result.getError());
    }
}
