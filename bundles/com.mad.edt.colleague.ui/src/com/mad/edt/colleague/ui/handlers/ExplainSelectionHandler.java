package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Handler for the "Explain Selection" editor context menu action.
 * Sends the selected code to the chat with a prompt asking for an explanation,
 * using a read-only tool profile so the model cannot write files.
 */
public class ExplainSelectionHandler extends AbstractHandler {

    private static final String READ_ONLY_PROFILE_MARKER = "[read-only profile]";

    @Override
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        final IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(theEvent).getActivePage();
        final IEditorPart editor = page.getActiveEditor();

        if (editor == null) {
            return null;
        }

        final String selectedCode = getSelectedText(editor);
        final String prompt = buildPrompt(selectedCode);
        final String context = READ_ONLY_PROFILE_MARKER + "\n" + selectedCode;

        ColleagueView.stageQuestion(prompt);
        ColleagueView.stageContext(context);

        try {
            final ColleagueView view = (ColleagueView) page.showView(ColleagueView.ID);
            view.applyStaged();
        } catch (final org.eclipse.ui.PartInitException ex) {
            throw new ExecutionException("cannot open the assistant view", ex);
        }
        return null;
    }

    /**
     * Extracts the selected text from the active editor.
     *
     * @param theEditor the active editor part
     * @return the selected text, or empty string if no selection
     */
    private String getSelectedText(final IEditorPart theEditor) {
        final ISelection selection = theEditor.getEditorSite().getSelectionProvider().getSelection();
        if (selection instanceof TextSelection) {
            return ((TextSelection) selection).getText();
        }
        return "";
    }

    /**
     * Builds the prompt for explaining the selected code.
     *
     * @param theSelectedCode the code selected by the user
     * @return the prompt string
     */
    public String buildPrompt(final String theSelectedCode) {
        if (theSelectedCode == null || theSelectedCode.isBlank()) {
            return "Объясни, что делает этот код (код не выделен). " + READ_ONLY_PROFILE_MARKER;
        }
        return "Объясни, что делает следующий код:\n\n```bsl\n" + theSelectedCode.trim() + "\n```\n\n"
                + READ_ONLY_PROFILE_MARKER;
    }
}