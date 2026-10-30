package com.mad.edt.colleague.agent;

/**
 * What the chat may do with the workspace on the next turn: plain conversation
 * without tools, a read-only planning pass that may only observe and lay out
 * a plan, or full execution including writes.
 */
public enum ChatMode {

    /** conversation only, no tools offered at all */
    ASK,
    /** tools that only read the workspace plus the plan board */
    PLAN,
    /** the full tool set, writes included */
    EXECUTE;

    /**
     * @return the stable name this mode travels under on the chat page wire
     */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return the next mode in the three-state cycle the controls walk
     *         through: ask to plan to execute and back to ask
     */
    public ChatMode next() {
        switch(this) {
            case ASK:
                return PLAN;
            case PLAN:
                return EXECUTE;
            default:
                return ASK;
        }
    }

    /**
     * Reads a mode back from the page wire. The page is a separate process,
     * so its input is treated as untrusted: surrounding blanks and a
     * different case are forgiven, anything unknown settles on the safest
     * mode rather than throwing at the UI thread.
     *
     * @param theWireName mode name as the page sent it, {@code null} tolerated
     * @return the named mode, {@link #ASK} for anything unrecognized
     */
    public static ChatMode of(final String theWireName) {
        if(theWireName == null) {
            return ASK;
        }
        final String trimmed = theWireName.trim();
        for(final ChatMode mode : values()) {
            if(mode.wireName().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return ASK;
    }
}
