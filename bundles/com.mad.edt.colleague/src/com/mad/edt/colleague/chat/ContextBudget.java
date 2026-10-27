package com.mad.edt.colleague.chat;

/**
 * Prompt-size guard for preloaded project context. A local reasoning model
 * processes the whole prompt before its first token, and an oversized
 * context pushes the reasoning pass past the generation budget before the
 * model takes any action. Trimming keeps both ends of the text - the search
 * hits that come first and the configuration picture that sits last - and
 * announces the cut so the model never mistakes a middle gap for the whole
 * story; the agent can still fetch what was dropped through its own tools.
 */
public final class ContextBudget {

    private static final String MARKER_PREFIX = "[context trimmed: ";
    private static final String MARKER_SUFFIX = " chars omitted]";

    private ContextBudget() {
        // utility
    }

    /**
     * @param theText preloaded context, {@code null} means none
     * @param theMaxChars hard ceiling for the returned text
     * @return the text trimmed from the middle down to the budget, with the
     *         removed span announced by a marker
     */
    public static String trimMiddle(final String theText, final int theMaxChars) {
        if(theText == null) {
            return "";
        }
        if(theText.length() <= theMaxChars) {
            return theText;
        }
        if(theMaxChars <= MARKER_PREFIX.length() + MARKER_SUFFIX.length() + 10) {
            // no honest marker fits this budget: plain cut, nothing left to say
            return theText.substring(0, theMaxChars);
        }
        // the marker counts the omitted span, and its own digit count changes
        // the span: start from an overestimate and converge, two passes suffice
        int markerChars = MARKER_PREFIX.length() + 5 + MARKER_SUFFIX.length();
        for(int pass = 0; pass < 3; pass++) {
            final int kept = theMaxChars - markerChars;
            final int omitted = theText.length() - kept;
            final String marker = MARKER_PREFIX + omitted + MARKER_SUFFIX;
            if(marker.length() == markerChars) {
                final int head = (int) (kept * 0.6);
                final int tail = kept - head;
                return theText.substring(0, head) + marker + theText.substring(theText.length() - tail);
            }
            markerChars = marker.length();
        }
        return theText.substring(0, theMaxChars);
    }
}
