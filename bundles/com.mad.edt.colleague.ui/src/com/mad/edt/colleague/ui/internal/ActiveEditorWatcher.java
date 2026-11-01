package com.mad.edt.colleague.ui.internal;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;

import com.mad.edt.colleague.chat.ActiveEditorContext;
import com.mad.edt.colleague.context.ProjectContext;

/**
 * Listens to workbench part activation and tracks the text editor the user
 * is looking at: file path, caret line and a bounded window of surrounding
 * lines. Feeds the autocontext supplier so every question knows where the
 * user is standing in the configuration tree.
 */
public final class ActiveEditorWatcher {

    private final ActiveEditorContext ourContext = new ActiveEditorContext();

    /**
     * Registers part listeners on the given page.
     *
     * @param thePage workbench page to watch
     */
    public void attach(final IWorkbenchPage thePage) {
        if(thePage == null) {
            return;
        }
        thePage.addPartListener(new PartTracker());
        // also capture the currently active editor, in case the view opened
        // after the editor was already active
        final IEditorPart active = thePage.getActiveEditor();
        if(active instanceof ITextEditor) {
            snapshot((ITextEditor)active);
        }
    }

    /**
     * @return the current editor snapshot as model context, empty when no
     *         editor is tracked
     */
    public String render() {
        return ourContext.render();
    }

    private void snapshot(final ITextEditor theEditor) {
        final IFile file = editorFile(theEditor);
        if(file == null) {
            ourContext.clear();
            return;
        }
        final String path = file.getProjectRelativePath().toPortableString();
        final int caretLine = caretLine(theEditor);
        final String text = documentText(theEditor);
        ourContext.show(path, caretLine, text);
    }

    private static IFile editorFile(final ITextEditor theEditor) {
        final IEditorPart editor = theEditor.getAdapter(IEditorPart.class);
        if(editor == null) {
            return null;
        }
        final IEditorInput input = editor.getEditorInput();
        if(input == null) {
            return null;
        }
        return input.getAdapter(IFile.class);
    }

    private static int caretLine(final ITextEditor theEditor) {
        final ITextViewer viewer = theEditor.getAdapter(ITextViewer.class);
        if(viewer == null) {
            return 1;
        }
        // get the caret line via the text widget
        final org.eclipse.swt.custom.StyledText textWidget = viewer.getTextWidget();
        if(textWidget == null) {
            return 1;
        }
        return textWidget.getLineAtOffset(textWidget.getCaretOffset()) + 1;
    }

    private static String documentText(final ITextEditor theEditor) {
        final ITextViewer viewer = theEditor.getAdapter(ITextViewer.class);
        if(viewer == null) {
            return "";
        }
        return viewer.getDocument() == null ? "" : viewer.getDocument().get();
    }

    /**
     * Tries to extract text and caret position from a non-ITextEditor editor
     * using common adapter patterns.
     */
    private void snapshotGeneric(final IEditorPart theEditor) {
        // Try to get IFile from editor input
        final IEditorInput input = theEditor.getEditorInput();
        if(input == null) {
            ourContext.clear();
            return;
        }
        final IFile file = input.getAdapter(IFile.class);
        if(file == null) {
            ourContext.clear();
            return;
        }
        final String path = file.getProjectRelativePath().toPortableString();

        // Try to get document via IDocumentProvider adapter
        final IDocumentProvider provider = theEditor.getAdapter(IDocumentProvider.class);
        if(provider == null) {
            ourContext.clear();
            return;
        }
        try {
            final org.eclipse.jface.text.IDocument doc = provider.getDocument(input);
            if(doc == null) {
                ourContext.clear();
                return;
            }
            final String text = doc.get();
            
            // Try to get caret line via ITextViewer or ISelectionProvider
            int caretLine = 1;
            final ITextViewer viewer = theEditor.getAdapter(ITextViewer.class);
            if(viewer != null) {
                final org.eclipse.swt.custom.StyledText textWidget = viewer.getTextWidget();
                if(textWidget != null) {
                    caretLine = textWidget.getLineAtOffset(textWidget.getCaretOffset()) + 1;
                }
            } else {
                // Try selection provider
                final ISelectionProvider selProvider = theEditor.getAdapter(ISelectionProvider.class);
                if(selProvider != null) {
                    final org.eclipse.jface.viewers.ISelection sel = selProvider.getSelection();
                    if(sel instanceof org.eclipse.jface.text.TextSelection) {
                        caretLine = ((org.eclipse.jface.text.TextSelection)sel).getStartLine() + 1;
                    }
                }
            }
            
            ourContext.show(path, caretLine, text);
        } catch(final Exception ex) {
            ourContext.clear();
        }
    }

    /**
     * Tracks part activation, focusing on text editors.
     */
    private final class PartTracker implements IPartListener2 {

        @Override
        public void partActivated(final IWorkbenchPartReference theRef) {
            if(!(theRef instanceof IEditorReference)) {
                return;
            }
            final IEditorPart editor = ((IEditorReference)theRef).getEditor(false);
            if(editor == null) {
                return;
            }
            // Try ITextEditor first (standard Eclipse text editors)
            if(editor instanceof ITextEditor) {
                snapshot((ITextEditor)editor);
                return;
            }
            // Try to adapt to ITextEditor (some editors support this)
            final ITextEditor adapted = editor.getAdapter(ITextEditor.class);
            if(adapted != null) {
                snapshot(adapted);
                return;
            }
            // Fallback: try to get document and caret via generic adapters
            snapshotGeneric(editor);
        }

        @Override
        public void partBroughtToTop(final IWorkbenchPartReference theRef) {
        }

        @Override
        public void partClosed(final IWorkbenchPartReference theRef) {
            if(!(theRef instanceof IEditorReference)) {
                return;
            }
            final IEditorPart editor = ((IEditorReference)theRef).getEditor(false);
            if(editor instanceof ITextEditor) {
                ourContext.clear();
            }
        }

        @Override
        public void partDeactivated(final IWorkbenchPartReference theRef) {
        }

        @Override
        public void partOpened(final IWorkbenchPartReference theRef) {
        }

        @Override
        public void partHidden(final IWorkbenchPartReference theRef) {
        }

        @Override
        public void partVisible(final IWorkbenchPartReference theRef) {
        }

        @Override
        public void partInputChanged(final IWorkbenchPartReference theRef) {
            if(!(theRef instanceof IEditorReference)) {
                return;
            }
            final IEditorPart editor = ((IEditorReference)theRef).getEditor(false);
            if(editor instanceof ITextEditor) {
                snapshot((ITextEditor)editor);
            }
        }
    }
}