package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Opens the assistant view and stages a question that includes the name of
 * the selected project object, so the model gets the surrounding context.
 */
public class AskSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        final IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(theEvent).getActivePage();

        String context = "";
        if(page.getSelection() instanceof IStructuredSelection) {
            final Object first = ((IStructuredSelection)page.getSelection()).getFirstElement();
            if(first != null) {
                context = String.valueOf(first);
            }
        }
        final String question = context.isEmpty()
            ? "Объясни, что это за объект и как с ним работать"
            : "Объект: " + context + ". Объясни, что это за объект и как с ним работать в 1С";
        ColleagueView.stageQuestion(question);

        try {
            final ColleagueView view = (ColleagueView)page.showView(ColleagueView.ID);
            view.applyStagedQuestion();
        } catch (final PartInitException ex) {
            throw new ExecutionException("cannot open the assistant view", ex);
        }
        return null;
    }
}
