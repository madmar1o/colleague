package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.mad.edt.colleague.context.ProjectContext;
import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Opens the assistant view with a description of the selected project object
 * (and its project) attached as context, so the model answers about the real
 * workspace artifact.
 */
public class AskSelectionHandler extends AbstractHandler {

    private static final int CONTEXT_CHAR_BUDGET = 5_000;

    @Override
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        final IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(theEvent).getActivePage();

        final StringBuilder context = new StringBuilder();
        final Object selection = page.getSelection();
        IResource resource = null;
        if(selection instanceof IStructuredSelection) {
            final Object first = ((IStructuredSelection)selection).getFirstElement();
            resource = adaptResource(first);
        }
        if(resource != null && resource.exists()) {
            context.append(ProjectContext.describeProject(resource.getProject()));
            context.append('\n');
            context.append(ProjectContext.describeResource(resource, CONTEXT_CHAR_BUDGET));
        }

        final String question = resource == null
            ? "Объясни, что это за объект и как с ним работать"
            : "Объясни, что это за объект (см. приложенный контекст) и как с ним работать в 1С";
        ColleagueView.stageQuestion(question);
        ColleagueView.stageContext(context.toString());

        try {
            final ColleagueView view = (ColleagueView)page.showView(ColleagueView.ID);
            view.applyStaged();
        } catch (final PartInitException ex) {
            throw new ExecutionException("cannot open the assistant view", ex);
        }
        return null;
    }

    private static IResource adaptResource(final Object theElement) {
        if(theElement instanceof IResource) {
            return (IResource)theElement;
        }
        if(theElement instanceof org.eclipse.core.runtime.IAdaptable) {
            return ((org.eclipse.core.runtime.IAdaptable)theElement).getAdapter(IResource.class);
        }
        return null;
    }
}
