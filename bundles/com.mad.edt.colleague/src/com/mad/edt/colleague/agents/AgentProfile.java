package com.mad.edt.colleague.agents;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One agent profile loaded from a project markdown file: what the agent is
 * for, which model and step budget it asks for, where it may run (this chat
 * or as a helper of another agent), and which tools it is allowed to see. The
 * body of the file is the system prompt of that agent.
 */
public final class AgentProfile {

    /** Where a profile may be used. */
    public enum Mode {
        /** offered for the chat the user talks to */
        PRIMARY,
        /** usable when one agent delegates a step to another */
        SUBAGENT,
        /** usable in both roles, the default when the header says nothing */
        ALL
    }

    private final String name;
    private final String description;
    private final String system;
    private final String modelName;
    private final int maxSteps;
    private final Mode mode;
    private final Set<String> toolNames;
    private final boolean hidden;

    /**
     * @param theName profile name, the file name without the extension
     * @param theDescription one-line summary shown in the profile chip
     * @param theSystem system prompt of the agent, the body of its file
     * @param theModelName model this profile asks for, {@code null} to keep
     *            whatever the chat uses
     * @param theMaxSteps model round-trips allowed for one task, {@code 0}
     *            when the header set no budget
     * @param theMode where the profile may run
     * @param theToolNames tools the profile may see, empty when it narrows
     *            nothing
     * @param theHidden whether the profile stays out of the profile chip
     */
    public AgentProfile(final String theName, final String theDescription, final String theSystem,
        final String theModelName, final int theMaxSteps, final Mode theMode,
        final Set<String> theToolNames, final boolean theHidden) {
        name = theName;
        description = theDescription == null ? "" : theDescription;
        system = theSystem == null ? "" : theSystem;
        modelName = theModelName;
        maxSteps = theMaxSteps;
        mode = theMode == null ? Mode.ALL : theMode;
        toolNames = Collections.unmodifiableSet(
            new LinkedHashSet<>(theToolNames == null ? Set.<String>of() : theToolNames));
        hidden = theHidden;
    }

    /** @return profile name as shown in the chip */
    public String getName() {
        return name;
    }

    /** @return one-line summary */
    public String getDescription() {
        return description;
    }

    /** @return system prompt of this agent */
    public String getSystem() {
        return system;
    }

    /** @return requested model name, {@code null} when unset */
    public String getModelName() {
        return modelName;
    }

    /** @return step budget, {@code 0} when the profile sets none */
    public int getMaxSteps() {
        return maxSteps;
    }

    /** @return where this profile may run */
    public Mode getMode() {
        return mode;
    }

    /** @return tools the profile may see, empty when it narrows nothing */
    public Set<String> getToolNames() {
        return toolNames;
    }

    /** @return whether the profile is kept out of the visible list */
    public boolean isHidden() {
        return hidden;
    }
}
