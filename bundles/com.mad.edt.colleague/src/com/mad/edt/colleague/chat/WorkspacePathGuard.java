package com.mad.edt.colleague.chat;

/**
 * Decides whether a path taken from a chat answer may be opened in the editor
 * at all. The page renders {@code path:line} citations of the workspace files
 * the model talks about, but the text of an answer is model output: it can
 * carry an absolute path, a drive letter or {@code ..} hops that would walk
 * out of the workspace. The same rules the file tools apply to their own path
 * arguments are applied here before a click reaches the workbench. Stateless,
 * so any layer can consult it without pulling in the workbench API.
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {
        // utility: one rule set, no instances to keep
    }

    /**
     * Judges one path text as it arrived from the page.
     *
     * @param thePath path text to judge, {@code null} is never safe
     * @return whether the path is workspace-relative and stays inside the
     *         workspace tree
     */
    public static boolean isSafe(final String thePath) {
        if(thePath == null || thePath.isEmpty() || thePath.startsWith("/")
            || thePath.startsWith("\\") || thePath.contains(":")
            || thePath.contains("\\")) {
            return false;
        }
        final String[] segments = thePath.split("/");
        if(segments.length < 2) {
            return false;
        }
        for(final String segment : segments) {
            if(segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }
}
