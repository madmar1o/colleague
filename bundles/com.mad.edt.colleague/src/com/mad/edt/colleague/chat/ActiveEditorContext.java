package com.mad.edt.colleague.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the editor the user is looking at, reduced to what one answer
 * needs: the file path, the caret line and a bounded window of lines around
 * it. The window is cut when the snapshot is taken, so holding it costs one
 * excerpt instead of a whole document, and clearing it when the editor closes
 * keeps a file the user already left out of later questions.
 */
public final class ActiveEditorContext {

    /** Lines kept on each side of the caret line. */
    public static final int WINDOW_RADIUS = 40;

    /** Rendered snapshot, empty when no editor is tracked. */
    private String ourText = "";

    /** Creates an empty snapshot: no question carries editor context yet. */
    public ActiveEditorContext() {
    }

    /**
     * Replaces the snapshot with the window around one caret line. A blank
     * path, a missing document or an empty one means there is no editor worth
     * describing, which clears the snapshot instead of keeping stale text.
     *
     * @param thePath workspace-relative path of the file, blank clears it
     * @param theCaretLine 1-based caret line, clamped into the document
     * @param theDocumentText full document text, may be {@code null}
     */
    public synchronized void show(final String thePath, final int theCaretLine,
        final String theDocumentText) {
        if(thePath == null || thePath.isBlank() || theDocumentText == null
            || theDocumentText.isEmpty()) {
            clear();
            return;
        }
        final List<String> lines = lines(theDocumentText);
        if(lines.isEmpty()) {
            clear();
            return;
        }
        final int caret = Math.max(1, Math.min(lines.size(), theCaretLine));
        final int from = Math.max(1, caret - WINDOW_RADIUS);
        final int to = Math.min(lines.size(), caret + WINDOW_RADIUS);
        final StringBuilder snapshot = new StringBuilder("Active editor: ")
            .append(thePath.trim()).append(", caret line ").append(caret).append('\n');
        for(int line = from; line <= to; line++) {
            snapshot.append(line).append(": ").append(lines.get(line - 1)).append('\n');
        }
        ourText = snapshot.toString();
    }

    /** Drops the snapshot; called when the tracked editor closes. */
    public synchronized void clear() {
        ourText = "";
    }

    /**
     * @return the snapshot as model context, empty when no editor is tracked
     */
    public synchronized String render() {
        return ourText;
    }

    /**
     * @param theText document text, line breaks of either kind
     * @return its lines without carriage returns; one trailing line break is
     *         just the end of the last line, not an extra empty line
     */
    private static List<String> lines(final String theText) {
        final String normalized = theText.replace("\r\n", "\n").replace('\r', '\n');
        final List<String> lines = new ArrayList<>();
        for(final String line : normalized.split("\n", -1)) {
            lines.add(line);
        }
        if(!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
