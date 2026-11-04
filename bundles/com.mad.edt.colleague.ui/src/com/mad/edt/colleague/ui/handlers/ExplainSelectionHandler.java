package com.mad.edt.colleague.ui.handlers;

/**
 * Handler for the "Explain selection" editor context-menu action: it asks
 * the model to explain the selected code and runs the question on the
 * read-only agent profile, so the answer can never come from a model
 * holding write tools.
 */
public class ExplainSelectionHandler extends SelectionToChatHandler {

    /**
     * Builds the explanation prompt around the selected code.
     *
     * @param theSelectedCode the code selected in the editor, possibly empty
     * @return the prompt asking for the explanation
     */
    @Override
    protected String buildPrompt(final String theSelectedCode) {
        if (theSelectedCode == null || theSelectedCode.isBlank()) {
            return "Объясни код в месте каретки (выделение не задано).";
        }
        return "Объясни, что делает следующий код:\n\n```bsl\n" + theSelectedCode.trim() + "\n```";
    }
}
