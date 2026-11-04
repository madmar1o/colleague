package com.mad.edt.colleague.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code path:line} reference found in a chat answer: the model cites the
 * workspace file it talks about, and the click path turns that citation into
 * an editor jump. The token shape is workspace-relative (project folder first,
 * e.g. {@code Catalogs/Orders/Orders.xml:7}), so the same guard that the file
 * tools use keeps the referenced file inside the workspace. Immutable value
 * object; parsing is static and stateless.
 */
public final class ChatFileLink {

    /**
     * path:line token: at least one directory segment ending in '/', then the
     * file segment, then ':' and digits. The lookbehind keeps URLs
     * ({@code https://host/path:8080}) out: their host part is preceded by
     * {@code //} or a scheme colon.
     */
    private static final Pattern LINK_TOKEN = Pattern.compile(
        "(?<![\\w:/.-])((?:[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*/)+"
            + "[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*):(\\d{1,7})");

    private final String ourPath;
    private final int ourLine;

    private ChatFileLink(final String thePath, final int theLine) {
        ourPath = thePath;
        ourLine = theLine;
    }

    /**
     * Collects every {@code path:line} token of one answer text, in order.
     * A token whose line is {@code 0} is not a line reference and is skipped.
     *
     * @param theText answer text, {@code null} finds nothing
     * @return the links in the order they appear, empty when there are none
     */
    public static List<ChatFileLink> findIn(final String theText) {
        final List<ChatFileLink> links = new ArrayList<>();
        if(theText == null) {
            return links;
        }
        final Matcher matcher = LINK_TOKEN.matcher(theText);
        while(matcher.find()) {
            final int line = Integer.parseInt(matcher.group(2));
            if(line > 0) {
                links.add(new ChatFileLink(matcher.group(1), line));
            }
        }
        return links;
    }

    /**
     * Decides whether a path may be opened at all: workspace-relative with a
     * project segment, no drive letters, no absolute start, no {@code ..} or
     * {@code .} hops out of the workspace.
     *
     * @param thePath path text to judge, {@code null} is never safe
     * @return whether the path stays inside the workspace tree
     */
    public static boolean isWorkspaceSafe(final String thePath) {
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

    /** @return workspace-relative file path of the reference */
    public String getPath() {
        return ourPath;
    }

    /** @return 1-based line the reference points at */
    public int getLine() {
        return ourLine;
    }
}
