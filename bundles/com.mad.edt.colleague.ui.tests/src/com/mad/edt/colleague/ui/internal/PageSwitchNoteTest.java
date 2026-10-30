package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Pins the one-line trace of a page switch. The chat page and the contacts
 * page share one stack, so the only way to tell afterwards who moved the user
 * back to the provider list is the cause carried by this note: the back arrow
 * of a chat panel, an emptied tab folder, or the ordinary tab build. The test
 * keeps the shape stable so a log parser (and a human reading the console)
 * sees the same fields in the same order.
 */
class PageSwitchNoteTest {

    @Test
    void carriesPageCauseAndTabCount() {
        assertEquals("contacts (cause=tabs empty, tabs=0)",
            PageSwitchNote.describe("contacts", "tabs empty", 0, ""));
    }

    @Test
    void appendsTheDetailWhenOneIsGiven() {
        assertEquals("chat tabs (cause=back arrow, tabs=2, detail=qwen3:4b)",
            PageSwitchNote.describe("chat tabs", "back arrow", 2, "qwen3:4b"));
    }

    @Test
    void omitsAnEmptyDetailInsteadOfPrintingAnEmptyField() {
        final String note = PageSwitchNote.describe("contacts", "back arrow", 1, "   ");
        assertFalse(note.contains("detail="), "a blank detail must not reach the log");
        assertEquals("contacts (cause=back arrow, tabs=1)", note);
    }
}
