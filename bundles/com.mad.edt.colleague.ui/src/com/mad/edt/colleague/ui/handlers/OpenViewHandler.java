package com.mad.edt.colleague.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Opens (or focuses) the Colleague assistant view.
 */
public class OpenViewHandler extends AbstractHandler {

    @Override
    public Object execute(final ExecutionEvent theEvent) throws ExecutionException {
        try {
            final IWorkbenchPage page =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.showView(ColleagueView.ID);
        } catch (final PartInitException ex) {
            throw new ExecutionException("cannot open the assistant view", ex);
        }
        return null;
    }
}
