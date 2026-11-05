package com.mad.edt.colleague.chat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot of the editor the user is looking at. A question asked with the
 * caret standing in a method must carry that method, not the whole file and
 * not the first lines of it, so the window is taken around the caret and cut
 * at a fixed radius; closing the editor must drop the snapshot so a later
 * question never answers from a file the user already left.
 */
class ActiveEditorContextTest {

    private static String document(final int theLines) {
        final StringBuilder text = new StringBuilder();
        for(int line = 1; line <= theLines; line++) {
            text.append("line").append(line).append('\n');
        }
        return text.toString();
    }

    private static int numberedLines(final String theText) {
        int counted = 0;
        for(final String line : theText.split("\n")) {
            if(!line.isEmpty() && Character.isDigit(line.charAt(0))) {
                counted++;
            }
        }
        return counted;
    }

    @Test
    @DisplayName("the snapshot names the file, the caret line and the window around it")
    void windowAroundCaret() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Demo/src/CommonModules/Общий/Module.bsl", 100, document(200));
        final String rendered = context.render();

        Assertions.assertTrue(rendered.contains("Demo/src/CommonModules/Общий/Module.bsl"),
            rendered);
        Assertions.assertTrue(rendered.contains("caret line 100"), rendered);
        Assertions.assertTrue(rendered.contains("60: line60"), rendered);
        Assertions.assertTrue(rendered.contains("100: line100"), rendered);
        Assertions.assertTrue(rendered.contains("140: line140"), rendered);
        Assertions.assertFalse(rendered.contains("59: line59"), rendered);
        Assertions.assertFalse(rendered.contains("141: line141"), rendered);
    }

    @Test
    @DisplayName("a caret near the start begins the window at the first line")
    void caretNearStartBeginsAtFirstLine() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 5, document(200));
        final String rendered = context.render();

        Assertions.assertTrue(rendered.contains("1: line1"), rendered);
        Assertions.assertTrue(rendered.contains("45: line45"), rendered);
        Assertions.assertFalse(rendered.contains("46: line46"), rendered);
    }

    @Test
    @DisplayName("a caret near the end stops the window at the last line")
    void caretNearEndStopsAtLastLine() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 199, document(200));
        final String rendered = context.render();

        Assertions.assertTrue(rendered.contains("159: line159"), rendered);
        Assertions.assertTrue(rendered.contains("200: line200"), rendered);
        Assertions.assertFalse(rendered.contains("158: line158"), rendered);
    }

    @Test
    @DisplayName("the window never grows past the radius on either side")
    void windowNeverExceedsTheRadius() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 500, document(1000));

        Assertions.assertEquals(2 * ActiveEditorContext.WINDOW_RADIUS + 1,
            numberedLines(context.render()));
    }

    @Test
    @DisplayName("closing the editor drops the snapshot")
    void clearedSnapshotIsEmpty() {
        final ActiveEditorContext context = new ActiveEditorContext();
        context.show("Module.bsl", 3, document(10));

        context.clear();

        Assertions.assertEquals("", context.render());
    }

    @Test
    @DisplayName("a blank path means no editor, so nothing is carried")
    void blankPathClearsSnapshot() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("  ", 3, document(10));

        Assertions.assertEquals("", context.render());
    }

    @Test
    @DisplayName("a missing document means no snapshot instead of an error")
    void nullTextClearsSnapshot() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 3, null);

        Assertions.assertEquals("", context.render());
    }

    @Test
    @DisplayName("an empty document carries nothing")
    void emptyDocumentCarriesNothing() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 3, "");

        Assertions.assertEquals("", context.render());
    }

    @Test
    @DisplayName("carriage returns of a CRLF document never reach the context")
    void crlfIsStripped() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 2, "первая\r\nвторая\r\nтретья\r\n");

        final String rendered = context.render();
        Assertions.assertFalse(rendered.contains("\r"), rendered.replace("\r", "<CR>"));
        Assertions.assertTrue(rendered.contains("2: вторая"), rendered);
    }

    @Test
    @DisplayName("a caret line outside the document is clamped, not rejected")
    void caretLineIsClampedIntoTheDocument() {
        final ActiveEditorContext context = new ActiveEditorContext();
        context.show("Module.bsl", 0, document(20));
        Assertions.assertTrue(context.render().contains("caret line 1"),
            context.render());

        context.show("Module.bsl", 5_000, document(20));
        Assertions.assertTrue(context.render().contains("caret line 20"),
            context.render());
    }

    @Test
    @DisplayName("a trailing line break does not invent an empty last line")
    void trailingLineBreakAddsNoLine() {
        final ActiveEditorContext context = new ActiveEditorContext();

        context.show("Module.bsl", 2, "первая\nвторая\n");

        final String rendered = context.render();
        Assertions.assertTrue(rendered.contains("2: вторая"), rendered);
        Assertions.assertFalse(rendered.contains("3: "), rendered);
    }
}
