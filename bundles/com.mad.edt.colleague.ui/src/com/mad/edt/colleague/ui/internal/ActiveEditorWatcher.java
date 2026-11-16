package com.mad.edt.colleague.ui.internal;

import com.mad.edt.colleague.ColleagueLog;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
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
import org.eclipse.xtext.ui.editor.XtextEditor;

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
        ColleagueLog.info("[colleague] ActiveEditorWatcher: attach called");
        thePage.addPartListener(new PartTracker());
        // also capture the currently active editor, in case the view opened
        // after the editor was already active
        final IEditorPart active = thePage.getActiveEditor();
        if(active != null) {
            ColleagueLog.info("[colleague] ActiveEditorWatcher: initial active editorClass=" + active.getClass().getName());
            // Try XtextEditor first (1C EDT editors are Xtext-based)
            final XtextEditor xtextEditor = active.getAdapter(XtextEditor.class);
            ColleagueLog.info("[colleague] ActiveEditorWatcher: XtextEditor adapter=" + xtextEditor);
            if(xtextEditor != null) {
                snapshotXtext(xtextEditor);
                return;
            }
            if(active instanceof ITextEditor) {
                snapshot((ITextEditor)active);
            } else {
                final ITextEditor adapted = active.getAdapter(ITextEditor.class);
                ColleagueLog.info("[colleague] ActiveEditorWatcher: ITextEditor adapter=" + adapted);
                if(adapted != null) {
                    snapshot(adapted);
                } else {
                    snapshotGeneric(active);
                }
            }
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

    /**
     * Snapshot using XtextEditor (1C EDT editors are Xtext-based).
     */
    private void snapshotXtext(final XtextEditor theEditor) {
        final IFile file = editorFile(theEditor);
        if(file == null) {
            ourContext.clear();
            return;
        }
        final String path = file.getProjectRelativePath().toPortableString();
        
        // Get document text via ITextViewer adapter (XtextEditor provides this)
        String text = "";
        final ITextViewer viewer = theEditor.getAdapter(ITextViewer.class);
        if(viewer != null && viewer.getDocument() != null) {
            text = viewer.getDocument().get();
        }
        if(text.isEmpty()) {
            text = documentText(theEditor);
        }
        
        // Get caret/selection line
        int caretLine = 1;
        if(viewer != null) {
            final org.eclipse.swt.custom.StyledText textWidget = viewer.getTextWidget();
            if(textWidget != null) {
                caretLine = textWidget.getLineAtOffset(textWidget.getCaretOffset()) + 1;
            }
        }
        // Also try selection provider for selection range
        final ISelectionProvider selProvider = theEditor.getAdapter(ISelectionProvider.class);
        if(selProvider != null) {
            final org.eclipse.jface.viewers.ISelection sel = selProvider.getSelection();
            if(sel instanceof org.eclipse.jface.text.TextSelection) {
                caretLine = ((org.eclipse.jface.text.TextSelection)sel).getStartLine() + 1;
            }
        }
        
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
     * Tries to extract text and caret/selection position from a non-ITextEditor editor
     * using common adapter patterns and 1C-specific input methods.
     */
    private void snapshotGeneric(final IEditorPart theEditor) {
        // Try to get IFile from editor input
        final IEditorInput input = theEditor.getEditorInput();
        if (input == null) {
            ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric - no editor input");
            ourContext.clear();
            return;
        }
        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric inputClass=" + input.getClass().getName());

        // Try DtEditorProxyInput (1C-specific) to get the real file
        final IFile file = getFileFrom1cInput(input);
        if (file == null) {
            ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric - input doesn't adapt to IFile");
            ourContext.clear();
            return;
        }
        final String path = file.getProjectRelativePath().toPortableString();
        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric path=" + path);

        // Try to get document via IDocumentProvider adapter
        final IDocumentProvider provider = theEditor.getAdapter(IDocumentProvider.class);
        if (provider == null) {
            ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric - editor doesn't adapt to IDocumentProvider");
            ourContext.clear();
            return;
        }
        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric providerClass=" + provider.getClass().getName());
        try {
            final org.eclipse.jface.text.IDocument doc = provider.getDocument(input);
            if (doc == null) {
                ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric - provider returned null document");
                ourContext.clear();
                return;
            }
            final String text = doc.get();
            ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric docLength=" + text.length());

            // Try to get caret/selection line via editor input's getSelection() (1C-specific)
            int caretLine = 1;
            try {
                final java.lang.reflect.Method getSelection = input.getClass().getMethod("getSelection");
                final Object sel = getSelection.invoke(input);
                if (sel instanceof ISelection) {
                    ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric has ISelection from input");
                    if (sel instanceof TextSelection) {
                        final TextSelection textSel = (TextSelection) sel;
                        caretLine = textSel.getStartLine() + 1;
                        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric caretLine from input TextSelection=" + caretLine);
                    }
                }
            } catch (final Exception ignored) {
                // not a 1C input with getSelection()
            }

            // Fallback: Try to get caret line via ITextViewer or ISelectionProvider on editor
            if (caretLine == 1) {
                final ITextViewer viewer = theEditor.getAdapter(ITextViewer.class);
                if (viewer != null) {
                    ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric has ITextViewer");
                    final org.eclipse.swt.custom.StyledText textWidget = viewer.getTextWidget();
                    if (textWidget != null) {
                        caretLine = textWidget.getLineAtOffset(textWidget.getCaretOffset()) + 1;
                        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric caretLine from StyledText=" + caretLine);
                    }
                } else {
                    ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric no ITextViewer, trying ISelectionProvider");
                    // Try selection provider
                    final ISelectionProvider selProvider = theEditor.getAdapter(ISelectionProvider.class);
                    if (selProvider != null) {
                        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric has ISelectionProvider");
                        final ISelection sel = selProvider.getSelection();
                        if (sel instanceof TextSelection) {
                            caretLine = ((TextSelection) sel).getStartLine() + 1;
                            ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric caretLine from TextSelection=" + caretLine);
                        } else {
                            ColleagueLog.warn("[colleague] ActiveEditorWatcher: snapshotGeneric selection is not TextSelection: " + sel);
                        }
                    } else {
                        ColleagueLog.info("[colleague] ActiveEditorWatcher: snapshotGeneric no ISelectionProvider");
                    }
                }
            }

            ourContext.show(path, caretLine, text);
            ColleagueLog.warn("[colleague] ActiveEditorWatcher: snapshotGeneric OK path=" + path + " line=" + caretLine + " chars=" + text.length());
        } catch (final Exception ex) {
            ColleagueLog.error("[colleague] ActiveEditorWatcher: snapshotGeneric failed: " + ex);
            ColleagueLog.error("[colleague] unexpected failure", ex);
            ourContext.clear();
        }
    }

    /**
     * Tries to extract IFile from 1C-specific editor inputs (DtEditorProxyInput, DtEditorInput, etc.).
     *
     * @param theInput editor input, may be a 1C proxy input
     * @return the workspace file, or {@code null} if it cannot be determined
     */
    private IFile getFileFrom1cInput(final IEditorInput theInput) {
        // 1. Standard IFile adapter - works for DtEditorInput
        final IFile standard = theInput.getAdapter(IFile.class);
        if (standard != null) {
            return standard;
        }
        // 2. EMF model navigation: getModel() -> EObject -> eResource -> URI -> IFile
        try {
            final java.lang.reflect.Method getModel = theInput.getClass().getMethod("getModel");
            final Object model = getModel.invoke(theInput);
            if (model instanceof EObject) {
                final EObject eObject = (EObject) model;
                final Resource resource = eObject.eResource();
                if (resource != null) {
                    final org.eclipse.emf.common.util.URI uri = resource.getURI();
                    if (uri.isPlatformResource()) {
                        return ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(uri.toPlatformString(true)));
                    }
                }
            }
        } catch (final Exception ignored) {
            // not a 1C input with getModel()
        }
        return null;
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
            ColleagueLog.info("[colleague] ActiveEditorWatcher: partActivated editorClass=" + editor.getClass().getName());
            // Try XtextEditor first (1C EDT editors are Xtext-based)
            final XtextEditor xtextEditor = editor.getAdapter(XtextEditor.class);
            ColleagueLog.info("[colleague] ActiveEditorWatcher: XtextEditor adapter=" + xtextEditor);
            if(xtextEditor != null) {
                snapshotXtext(xtextEditor);
                return;
            }
            if(editor instanceof ITextEditor) {
                snapshot((ITextEditor)editor);
                return;
            }
            // Try to adapt to ITextEditor (some editors support this)
            final ITextEditor adapted = editor.getAdapter(ITextEditor.class);
            ColleagueLog.info("[colleague] ActiveEditorWatcher: ITextEditor adapter=" + adapted);
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
            if(editor instanceof ITextEditor || editor.getAdapter(XtextEditor.class) != null) {
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
            if(editor == null) {
                return;
            }
            ColleagueLog.info("[colleague] ActiveEditorWatcher: partInputChanged editorClass=" + editor.getClass().getName());
            // Try XtextEditor first (1C EDT editors are Xtext-based)
            final XtextEditor xtextEditor = editor.getAdapter(XtextEditor.class);
            ColleagueLog.info("[colleague] ActiveEditorWatcher: XtextEditor adapter=" + xtextEditor);
            if(xtextEditor != null) {
                snapshotXtext(xtextEditor);
                return;
            }
            if(editor instanceof ITextEditor) {
                snapshot((ITextEditor)editor);
            } else {
                final ITextEditor adapted = editor.getAdapter(ITextEditor.class);
                ColleagueLog.info("[colleague] ActiveEditorWatcher: ITextEditor adapter=" + adapted);
                if(adapted != null) {
                    snapshot(adapted);
                } else {
                    snapshotGeneric(editor);
                }
            }
        }
    }
}