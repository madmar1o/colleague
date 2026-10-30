package com.mad.edt.colleague.agent;

/**
 * Process-wide chat-mode switch. The mode decides what the next agent turn
 * may do with the workspace: {@link ChatMode#ASK} talks without offering any
 * tool, {@link ChatMode#PLAN} lets the agent observe the project and lay out
 * a plan but changes nothing, {@link ChatMode#EXECUTE} carries the full
 * write-capable tool set of the old "edit: on". It is process-wide state on
 * purpose: chat panels come and go with the workbench page, and the mode the
 * user picked in one session must be what the next panel opens with. The
 * legacy boolean surface stays for older callers: the old off/on toggle
 * cycles ASK to EXECUTE and back, skipping PLAN, and {@link #isOn()} is true
 * for any mode that hands tools to the model.
 */
public final class AgentMode {

    private static volatile ChatMode ourCurrent = ChatMode.ASK;

    private AgentMode() {
        // utility
    }

    /**
     * @return the mode the next agent turn runs under
     */
    public static ChatMode current() {
        return ourCurrent;
    }

    /**
     * @param theMode new mode for the following turns, {@code null} keeps the
     *            current one
     */
    public static synchronized void set(final ChatMode theMode) {
        if(theMode != null) {
            ourCurrent = theMode;
        }
    }

    /**
     * @return {@code true} when the assistant may hand any tool to the model,
     *         i.e. in {@link ChatMode#PLAN} and {@link ChatMode#EXECUTE}
     */
    public static boolean isOn() {
        return ourCurrent != ChatMode.ASK;
    }

    /**
     * Legacy compatibility entry point: {@code true} lands on the
     * write-capable {@link ChatMode#EXECUTE}, {@code false} on
     * {@link ChatMode#ASK}. A caller that means planning must go through
     * {@link #set(ChatMode)}.
     *
     * @param theEnabled old edit-mode flag
     */
    public static synchronized void setOn(final boolean theEnabled) {
        ourCurrent = theEnabled ? ChatMode.EXECUTE : ChatMode.ASK;
    }

    /**
     * Flips the legacy switch and reports the old-style flag: ASK jumps
     * straight to the write-capable EXECUTE, any agent mode falls back to
     * ASK. The plan mode is only reachable through the three-state control.
     *
     * @return the new legacy flag, {@code true} for an agent mode
     */
    public static synchronized boolean toggle() {
        ourCurrent = ourCurrent == ChatMode.ASK ? ChatMode.EXECUTE : ChatMode.ASK;
        return ourCurrent != ChatMode.ASK;
    }
}
