package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Base class of the editor context-menu actions that send the selected code
 * to the chat. The flow is one for every concrete action: read the editor
 * selection, stage the prompt of the concrete action together with the
 * selected code and the read-only agent profile picked from the workspace
 * catalog, then open the chat on that profile, so the model may explain or
 * draft code but physically cannot edit project files.
 */
public abstract class SelectionToChatHandler extends AbstractHandler {

    @Override
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        final IWorkbenchPage page =
            HandlerUtil.getActiveWorkbenchWindowChecked(theEvent).getActivePage();
        final IEditorPart editor = page.getActiveEditor();
        if (editor == null) {
            return null;
        }
        final String selectedCode = getSelectedText(editor);
        try {
            final ColleagueView view = (ColleagueView) page.showView(ColleagueView.ID);
            ColleagueView.stageQuestion(buildPrompt(selectedCode));
            ColleagueView.stageContext(selectedCode);
            // Prefer the read-only code-explainer profile; fall back to its
            // name so the tab is still created even before the catalog loads.
            ColleagueView.stageProfile("code-explainer");
            view.applyStaged();
        } catch (final PartInitException ex) {
            throw new ExecutionException("cannot open the assistant view", ex);
        }
        return null;
    }

    /**
     * Extracts the text selected in the editor the action was called on.
     *
     * @param theEditor the active editor part
     * @return the selected text, empty when the editor reported no text
     *         selection
     */
    private static String getSelectedText(final IEditorPart theEditor) {
        if (theEditor.getEditorSite() == null
                || theEditor.getEditorSite().getSelectionProvider() == null) {
            return "";
        }
        final ISelection selection =
            theEditor.getEditorSite().getSelectionProvider().getSelection();
        if (selection instanceof TextSelection) {
            return ((TextSelection) selection).getText();
        }
        return "";
    }

    /**
     * Builds the prompt of the concrete action around the selected code.
     *
     * @param theSelectedCode the code selected in the editor, possibly empty
     * @return the prompt to stage for the chat
     */
    protected abstract String buildPrompt(final String theSelectedCode);
}
