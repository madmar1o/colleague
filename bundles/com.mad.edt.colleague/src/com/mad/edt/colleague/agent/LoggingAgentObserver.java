package com.mad.edt.colleague.agent;

/**
 * Observer that prints the run trace to the platform output stream so every
 * agent turn is auditable from the outside: the round-trips it made, the
 * tools it decided, how big the results were and how long each step cost.
 * Long arguments and results stay clipped: the trace names what happened,
 * it does not duplicate file contents.
 */
public final class LoggingAgentObserver implements AgentObserver {

    private static final int CLIP_CHARS = 160;

    @Override
    public void onRound(final int theStep, final int theRequestedToolCalls) {
        if(theRequestedToolCalls == 0) {
            System.out.println("[colleague] agent round " + theStep + ": final answer");
        } else {
            System.out.println("[colleague] agent round " + theStep + ": asked "
                + theRequestedToolCalls + " tool call(s)");
        }
    }

    @Override
    public void onToolCall(final int theStep, final String theName, final String theArgumentsJson,
        final String theResult, final long theElapsedMs) {
        final int resultChars = theResult == null ? 0 : theResult.length();
        System.out.println("[colleague] agent step " + theStep + ": tool " + theName
            + " args=" + clip(theArgumentsJson) + " -> " + resultChars + " chars, "
            + theElapsedMs + " ms");
    }

    private static String clip(final String theText) {
        if(theText == null) {
            return "";
        }
        if(theText.length() <= CLIP_CHARS) {
            return theText;
        }
        return theText.substring(0, CLIP_CHARS) + "...";
    }
}
