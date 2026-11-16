package com.mad.edt.colleague.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final String title;
    private final String description;
    private final String system;
    private final String modelName;
    private final int maxSteps;
    private final Mode mode;
    private final Set<String> toolNames;
    private final boolean hidden;

    /**
     * @return a builder for a profile with the given id
     */
    public static Builder builder(final String theName) {
        return new Builder(theName);
    }

    /**
     * Builds an {@link AgentProfile} piece by piece, so the eight optional
     * facets of a profile stay named at their call sites instead of turning
     * into a positional argument wall.
     */
    public static final class Builder {

        private final String name;
        private String title = "";
        private String description = "";
        private String system = "";
        private String modelName;
        private int maxSteps;
        private Mode mode;
        private Set<String> toolNames;
        private boolean hidden;

        private Builder(final String theName) {
            name = theName;
        }

        /** @param theValue display name for pages and tabs, null for none */
        public Builder title(final String theValue) {
            title = theValue;
            return this;
        }

        /** @param theValue one-line summary shown in the profile chip */
        public Builder description(final String theValue) {
            description = theValue;
            return this;
        }

        /** @param theValue system prompt of the agent */
        public Builder system(final String theValue) {
            system = theValue;
            return this;
        }

        /** @param theValue model to use, null to keep the chat's own */
        public Builder model(final String theValue) {
            modelName = theValue;
            return this;
        }

        /** @param theValue step budget, 0 for no budget */
        public Builder maxSteps(final int theValue) {
            maxSteps = theValue;
            return this;
        }

        /** @param theValue where the profile may run, null means ALL */
        public Builder mode(final Mode theValue) {
            mode = theValue;
            return this;
        }

        /** @param theValue tools the profile may see, null for no narrowing */
        public Builder tools(final Set<String> theValue) {
            toolNames = theValue;
            return this;
        }

        /** @param theValue whether the profile stays out of the profile chip */
        public Builder hidden(final boolean theValue) {
            hidden = theValue;
            return this;
        }

        /** @return the built profile */
        public AgentProfile build() {
            return new AgentProfile(this);
        }
    }

    private AgentProfile(final Builder theBuilder) {
        name = theBuilder.name;
        title = theBuilder.title == null ? "" : theBuilder.title;
        description = theBuilder.description == null ? "" : theBuilder.description;
        system = theBuilder.system == null ? "" : theBuilder.system;
        modelName = theBuilder.modelName;
        maxSteps = theBuilder.maxSteps;
        mode = theBuilder.mode == null ? Mode.ALL : theBuilder.mode;
        toolNames = Collections.unmodifiableSet(
            new LinkedHashSet<>(theBuilder.toolNames == null ? Set.<String>of()
                : theBuilder.toolNames));
        hidden = theBuilder.hidden;
    }

    /** @return profile id as its file names it */
    public String getName() {
        return name;
    }

    /**
     * @return display name for pages and tabs: the {@code title} header when
     *         the file carries one, the file-name id otherwise
     */
    public String getDisplayName() {
        return title.isEmpty() ? name : title;
    }

    /** @return one-line summary */
    public String getDescription() {
        return description;
    }

    /** @return raw {@code title} header value, empty when the file set none */
    public String getTitle() {
        return title;
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

    /**
     * @return the allowed tool names in one stable (alphabetical) order, so
     *         saving a profile never shuffles its own file and the page form
     *         shows the same list the file carries
     */
    public List<String> getToolNamesInOrder() {
        final List<String> ordered = new ArrayList<>(toolNames);
        Collections.sort(ordered);
        return Collections.unmodifiableList(ordered);
    }

    /** @return whether the profile is kept out of the visible list */
    public boolean isHidden() {
        return hidden;
    }
}
