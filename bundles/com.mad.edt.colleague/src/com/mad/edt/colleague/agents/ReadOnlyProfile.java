package com.mad.edt.colleague.agents;

import java.util.List;

/**
 * Picks the profile an editor action should run on: the first chat-usable
 * profile whose tool list is non-empty and carries no write tool, so a
 * selection sent from the editor can never hand the model the right to
 * change project files.
 */
public final class ReadOnlyProfile {

    private ReadOnlyProfile() {
    }

    /**
     * Finds the first profile a chat tab may run whose tool list actually
     * narrows the catalog down to read-only tools.
     *
     * @param theCatalog the merged agent catalog, may be {@code null}
     * @return the first read-only chat profile, {@code null} when the
     *         catalog has none
     */
    public static AgentProfile firstReadOnly(final List<AgentProfile> theCatalog) {
        if (theCatalog == null) {
            return null;
        }
        for (final AgentProfile profile : theCatalog) {
            if (isReadOnly(profile)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Decides whether one profile is chat-usable and read-only: it has to
     * name its tools (an empty list narrows nothing, which is full access),
     * keep every write tool out and be allowed to run as the chat itself.
     *
     * @param theProfile profile to check, {@code null} is never read-only
     * @return whether a chat tab may run on this profile without write tools
     */
    public static boolean isReadOnly(final AgentProfile theProfile) {
        if (theProfile == null || theProfile.getToolNames().isEmpty()
                || theProfile.getMode() == AgentProfile.Mode.SUBAGENT) {
            return false;
        }
        for (final String tool : theProfile.getToolNames()) {
            if (ToolPolicy.WRITE_TOOLS.contains(tool)) {
                return false;
            }
        }
        return true;
    }
}
