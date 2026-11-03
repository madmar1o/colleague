package com.mad.edt.colleague.ui.handlers;

/**
 * Handler for the "Generate method" editor context-menu action: it asks the
 * model to draft a self-contained method from the selected code and runs
 * the question on the read-only agent profile, so the answer is text for
 * the user to apply, never a direct file change.
 */
public class GenerateMethodHandler extends SelectionToChatHandler {

    /**
     * Builds the method-generation prompt around the selected code.
     *
     * @param theSelectedCode the code selected in the editor, possibly empty
     * @return the prompt asking for the generated method
     */
    @Override
    protected String buildPrompt(final String theSelectedCode) {
        if (theSelectedCode == null || theSelectedCode.isBlank()) {
            return "Сгенерируй метод по месту в редакторе (выделение не задано).";
        }
        return "Сгенерируй метод на основе следующего фрагмента кода:\n\n```bsl\n"
                + theSelectedCode.trim() + "\n```\n\n"
                + "Метод должен быть самодостаточным, с понятным именем и параметрами.";
    }
}
