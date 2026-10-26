package com.mad.edt.colleague.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins how a model-written edit script is parsed: a JSON object with a file
 * path and an ordered list of search/replace pairs, with clear errors when
 * the JSON is malformed or fields are missing so the model can repair it.
 */
class EditScriptTest {

    @Test
    void parsesPathAndOrderedEditBlocks() {
        EditScript.ParseResult parsed = EditScript.parse(
            "{\"path\":\"src/a.bsl\",\"edits\":["
                + "{\"search\":\"Proc A\",\"replace\":\"Proc B\"},"
                + "{\"search\":\"EndProc\",\"replace\":\"End Proc\"}]}");
        assertTrue(parsed.isOk(), parsed.getError());
        assertEquals("src/a.bsl", parsed.getScript().getPath());
        assertEquals(2, parsed.getScript().getBlocks().size());
        assertEquals("Proc A", parsed.getScript().getBlocks().get(0).getSearchText());
        assertEquals("End Proc", parsed.getScript().getBlocks().get(1).getReplaceText());
    }

    @Test
    void missingPathIsRefusedNamingTheField() {
        EditScript.ParseResult parsed = EditScript.parse(
            "{\"edits\":[{\"search\":\"a\",\"replace\":\"b\"}]}");
        assertFalse(parsed.isOk());
        assertTrue(parsed.getError().contains("path"), parsed.getError());
    }

    @Test
    void missingEditsListIsRefusedNamingTheField() {
        EditScript.ParseResult parsed = EditScript.parse("{\"path\":\"a.bsl\"}");
        assertFalse(parsed.isOk());
        assertTrue(parsed.getError().contains("edits"), parsed.getError());
    }

    @Test
    void emptyEditsListIsRefused() {
        EditScript.ParseResult parsed =
            EditScript.parse("{\"path\":\"a.bsl\",\"edits\":[]}");
        assertFalse(parsed.isOk());
        assertTrue(parsed.getError().contains("edits"), parsed.getError());
    }

    @Test
    void editWithoutSearchFieldIsRefusedNamingThePosition() {
        EditScript.ParseResult parsed = EditScript.parse(
            "{\"path\":\"a.bsl\",\"edits\":[{\"replace\":\"b\"}]}");
        assertFalse(parsed.isOk());
        assertTrue(parsed.getError().contains("search"), parsed.getError());
    }

    @Test
    void malformedJsonIsRefusedNotCrashing() {
        EditScript.ParseResult parsed = EditScript.parse("{path: oops");
        assertFalse(parsed.isOk());
        assertTrue(parsed.getError().contains("not valid JSON"), parsed.getError());
    }
}
