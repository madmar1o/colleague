package com.mad.edt.colleague.agent;

/**
 * Process-wide edit-mode switch. While the mode is off, code-writing tools
 * are not offered to the agent at all; turning it on is a deliberate user
 * gesture, and every write still asks for confirmation.
 */
public final class AgentMode {

    private static volatile boolean ourEnabled;

    private AgentMode() {
        // utility
    }

    /**
     * @return {@code true} when the assistant may change workspace files
     */
    public static boolean isOn() {
        return ourEnabled;
    }

    /**
     * @param theEnabled new mode state
     */
    public static void setOn(final boolean theEnabled) {
        ourEnabled = theEnabled;
    }

    /** Flips the mode and reports the new state. */
    public static synchronized boolean toggle() {
        ourEnabled = !ourEnabled;
        return ourEnabled;
    }
}
