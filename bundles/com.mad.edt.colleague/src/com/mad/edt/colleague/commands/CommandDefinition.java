package com.mad.edt.colleague.commands;

/**
 * One user command loaded from a project markdown file: the name comes from
 * the file name, the header supplies the description and the optional agent
 * binding, and the body is the prompt template sent to the model when the
 * user invokes the command from the chat line.
 */
public final class CommandDefinition {

    private final String name;
    private final String description;
    private final String template;
    private final String agentName;
    private final boolean subagent;

    /**
     * @param theName command name, the file name without the extension
     * @param theDescription one-line summary for the command hints
     * @param theTemplate prompt body, {@code $ARGUMENTS} receives the rest
     *            of the user line
     * @param theAgentName agent profile the command asks for, {@code null}
     *            when the current chat settings stay
     * @param theSubagent whether the command asks to run as a separate
     *            sub-agent turn
     */
    public CommandDefinition(final String theName, final String theDescription,
        final String theTemplate, final String theAgentName, final boolean theSubagent) {
        name = theName;
        description = theDescription == null ? "" : theDescription;
        template = theTemplate == null ? "" : theTemplate;
        agentName = theAgentName;
        subagent = theSubagent;
    }

    /** @return command name as typed after the slash */
    public String getName() {
        return name;
    }

    /** @return one-line summary shown in the hints */
    public String getDescription() {
        return description;
    }

    /** @return prompt body with its {@code $ARGUMENTS} placeholder as written */
    public String getTemplate() {
        return template;
    }

    /** @return requested agent profile name, {@code null} when unset */
    public String getAgentName() {
        return agentName;
    }

    /** @return whether the command asks for a separate sub-agent turn */
    public boolean isSubagent() {
        return subagent;
    }
}
