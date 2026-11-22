package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

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
    @SuppressWarnings("java:S3516")
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        // Contract: AbstractHandler.execute requires an Object return; null is the
        // intended "no return value" for command handlers.
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
     * Two providers answer for one editor: the editor site replays only the
     * selection the part last announced and is left empty after the editor
     * was deactivated and reactivated, while a text editor's own provider
     * reads the live source viewer. The non-empty answer wins, the editor's
     * own one otherwise.
     *
     * @param theEditor the active editor part
     * @return the selected text, empty when neither provider reported text
     */
    private static String getSelectedText(final IEditorPart theEditor) {
        final ISelection picked = pickSelection(
            editorOwnSelection(theEditor), siteSelection(theEditor));
        return selectedTextIn(picked);
    }

    /**
     * Reads the selection straight from the text editor's own provider, the
     * one that answers from the source viewer rather than from announcements.
     *
     * @param theEditor the active editor part
     * @return the live selection, {@code null} when the part is no text
     *         editor or its provider is gone
     */
    private static ISelection editorOwnSelection(final IEditorPart theEditor) {
        if (!(theEditor instanceof ITextEditor)) {
            return null;
        }
        final ISelectionProvider provider =
            ((ITextEditor) theEditor).getSelectionProvider();
        return provider == null ? null : provider.getSelection();
    }

    /**
     * Reads the selection the editor part announced to the workbench.
     *
     * @param theEditor the active editor part
     * @return the announced selection, {@code null} when the part has no
     *         site or no selection provider
     */
    private static ISelection siteSelection(final IEditorPart theEditor) {
        if (theEditor.getEditorSite() == null
                || theEditor.getEditorSite().getSelectionProvider() == null) {
            return null;
        }
        return theEditor.getEditorSite().getSelectionProvider().getSelection();
    }

    /**
     * Chooses which of the two selections to read the code from: a text
     * selection carrying text outranks a blank one, and with neither
     * carrying text the editor's own answer is kept, because an empty
     * selection there is the honest state of the source viewer.
     *
     * @param theEditorSelection selection from the editor's own provider,
     *            possibly {@code null}
     * @param theSiteSelection selection announced to the workbench,
     *            possibly {@code null}
     * @return the selection to read, {@code null} when both providers are
     *         missing
     */
    static ISelection pickSelection(final ISelection theEditorSelection,
        final ISelection theSiteSelection) {
        if (!selectedTextIn(theEditorSelection).isEmpty()) {
            return theEditorSelection;
        }
        if (!selectedTextIn(theSiteSelection).isEmpty()) {
            return theSiteSelection;
        }
        return theEditorSelection != null ? theEditorSelection : theSiteSelection;
    }

    /**
     * @param theSelection a selection from either provider, possibly
     *            {@code null}
     * @return the text of a text selection, empty for any other selection
     *         or when it carries none
     */
    static String selectedTextIn(final ISelection theSelection) {
        if (theSelection instanceof TextSelection) {
            final String text = ((TextSelection) theSelection).getText();
            return text == null ? "" : text;
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
