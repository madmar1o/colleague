package com.mad.edt.colleague.ui.internal;

import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;

/**
 * Skeleton over {@link IPartListener2}: every part event arrives here with an
 * empty body, so listeners only override the few events they actually care
 * about instead of carrying six do-nothing methods each.
 */
abstract class PartListenerAdapter implements IPartListener2 {

    @Override
    public void partActivated(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partBroughtToTop(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partClosed(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partDeactivated(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partOpened(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partHidden(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partVisible(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }

    @Override
    public void partInputChanged(final IWorkbenchPartReference theRef) {
        // the skeleton routes no events by itself
    }
}
