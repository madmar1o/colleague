package com.mad.edt.colleague.ui.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.junit.jupiter.api.Test;

/**
 * Tests which of the two live selections the editor actions read their code
 * from: the text editor's own provider answers from the source viewer and
 * keeps the selection across focus changes, while the site provider only
 * replays what the part last announced and can be left empty after the
 * editor was deactivated and reactivated.
 */
class EditorSelectionPickTest {

    private static final IDocument EDITOR_DOC = new Document("код редактора");
    private static final IDocument SITE_DOC = new Document("код сайта");
    private static final IDocument EMPTY_DOC = new Document("");

    private static final ISelection EDITOR_SELECTED =
        new TextSelection(EDITOR_DOC, 0, EDITOR_DOC.get().length());
    private static final ISelection SITE_SELECTED =
        new TextSelection(SITE_DOC, 0, SITE_DOC.get().length());

    private static TextSelection blankIn(final IDocument theDocument) {
        return new TextSelection(theDocument, 0, 0);
    }

    @Test
    void editorOwnSelectionWinsWhileItCarriesText() {
        final ISelection picked =
            SelectionToChatHandler.pickSelection(EDITOR_SELECTED, blankIn(EMPTY_DOC));

        assertSame(EDITOR_SELECTED, picked);
    }

    @Test
    void siteSelectionIsUsedWhenTheEditorAnswerIsBlank() {
        final ISelection picked =
            SelectionToChatHandler.pickSelection(blankIn(EMPTY_DOC), SITE_SELECTED);

        assertSame(SITE_SELECTED, picked);
    }

    @Test
    void blankTextSelectionNeverOutranksARealOne() {
        // a provider answering with an empty TextSelection must not mask the
        // other provider's answer: that is exactly the stale-site-provider
        // case behind the empty explain-selection tab
        final ISelection picked = SelectionToChatHandler.pickSelection(
            new TextSelection(5, 12), EDITOR_SELECTED);

        assertSame(EDITOR_SELECTED, picked);
    }

    @Test
    void bothEmptyKeepsTheEditorsHonestEmptyAnswer() {
        final ISelection emptyFromEditor = blankIn(EMPTY_DOC);

        final ISelection picked = SelectionToChatHandler.pickSelection(
            emptyFromEditor, new TextSelection(1, 0));

        assertSame(emptyFromEditor, picked);
    }

    @Test
    void missingProvidersTolerateNullSelections() {
        assertSame(SITE_SELECTED, SelectionToChatHandler.pickSelection(null, SITE_SELECTED));
        assertSame(EDITOR_SELECTED, SelectionToChatHandler.pickSelection(EDITOR_SELECTED, null));
        assertNull(SelectionToChatHandler.pickSelection(null, null));
    }

    @Test
    void nonTextSelectionIsKeptOnlyWhenNothingBetterExists() {
        final ISelection structure = new StructuredSelection("x");

        assertSame(structure, SelectionToChatHandler.pickSelection(structure, null));
        assertSame(EDITOR_SELECTED,
            SelectionToChatHandler.pickSelection(structure, EDITOR_SELECTED));
    }

    @Test
    void selectedTextInReadsTextSelectionsOnly() {
        assertEquals("", SelectionToChatHandler.selectedTextIn(null));
        assertEquals("", SelectionToChatHandler.selectedTextIn(blankIn(EMPTY_DOC)));
        assertEquals("", SelectionToChatHandler.selectedTextIn(new TextSelection(0, 5)));
        assertEquals("Привет", SelectionToChatHandler.selectedTextIn(
            new TextSelection(new Document("Привет"), 0, 6)));
        assertEquals("", SelectionToChatHandler.selectedTextIn(new StructuredSelection("x")));
    }
}
