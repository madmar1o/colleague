package com.mad.edt.colleague.ui.internal;

import java.io.File;

import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;

/**
 * Static helpers shared by the embedded-browser panels: the browser backend
 * user-data preparation done once per JVM and clipboard writes requested by
 * the pages.
 */
final class ViewBridge {

    /** Where the embedded browser keeps its profile (cache, cookies). */
    private static final String EDGE_DATA_DIR_PROPERTY = "org.eclipse.swt.browser.EdgeDataDir";

    /** Selects the modern browser backend; the legacy one lacks DOM APIs our pages use. */
    private static final String DEFAULT_TYPE_PROPERTY = "org.eclipse.swt.browser.DefaultType";

    private static boolean ourDataDirChecked;

    private ViewBridge() {
        // utility
    }

    /**
     * Points the embedded browser at a dedicated temporary profile before any
     * browser control is created; the default location next to the JVM is
     * refused by the backend. Must run before the first {@code Browser}.
     */
    static synchronized void ensureUserDataDir() {
        if(ourDataDirChecked) {
            return;
        }
        ourDataDirChecked = true;
        if(System.getProperty(DEFAULT_TYPE_PROPERTY) == null) {
            System.setProperty(DEFAULT_TYPE_PROPERTY, "edge");
        }
        if(System.getProperty(EDGE_DATA_DIR_PROPERTY) != null) {
            return;
        }
        final File directory = new File(System.getProperty("java.io.tmpdir", "."),
            "colleague-browser-profile");
        if(!directory.isDirectory() && !directory.mkdirs()) {
            System.err.println("colleague: cannot create browser data directory "
                + directory.getAbsolutePath());
            return;
        }
        System.setProperty(EDGE_DATA_DIR_PROPERTY, directory.getAbsolutePath());
    }

    /**
     * Puts text on the system clipboard; the clipboard is disposed together
     * with the requesting control.
     *
     * @param theOwner control owning the clipboard lifetime
     * @param theText text to copy, ignored when empty
     */
    static void copyToClipboard(final Control theOwner, final String theText) {
        if(theText == null || theText.isEmpty() || theOwner.isDisposed()) {
            return;
        }
        final Clipboard clipboard = new Clipboard(theOwner.getDisplay());
        clipboard.setContents(new Object[] {theText},
            new Transfer[] {TextTransfer.getInstance()});
        theOwner.addListener(org.eclipse.swt.SWT.Dispose, event -> clipboard.dispose());
    }
}
