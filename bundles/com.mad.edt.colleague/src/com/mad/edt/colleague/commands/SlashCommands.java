package com.mad.edt.colleague.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one chat input line into a command invocation when it starts with
 * a slash and names a loaded command; otherwise the line is plain text.
 * A command whose template asks for arguments is not invoked on an empty
 * argument tail: the half-typed line stays in the chat instead of firing
 * the prompt at nothing.
 */
public final class SlashCommands {

    /** Placeholder inside a template body that receives the user arguments. */
    public static final String ARGUMENTS_PLACEHOLDER = "$ARGUMENTS";

    /**
     * One matched command line: the command it names plus the prompt text
     * with the arguments already filled in.
     *
     * @param command the matched command
     * @param prompt template with the argument tail substituted
     */
    public record Invocation(CommandDefinition command, String prompt) {
    }

    private final Map<String, CommandDefinition> byName;

    /**
     * @param theCommands loaded commands, first occurrence wins on a name clash
     */
    public SlashCommands(final List<CommandDefinition> theCommands) {
        final Map<String, CommandDefinition> names = new LinkedHashMap<>();
        for(final CommandDefinition command : theCommands) {
            names.putIfAbsent(command.getName(), command);
        }
        byName = Collections.unmodifiableMap(names);
    }

    /**
     * Matches the input against the command catalog.
     *
     * @param theLine raw user input
     * @return invocation with filled template, or null when the
     *         line is not a known slash command
     */
    public Invocation match(final String theLine) {
        if(theLine == null) {
            return null;
        }
        final String line = theLine.trim();
        if(!line.startsWith("/")) {
            return null;
        }
        final int space = line.indexOf(' ');
        final String name = line.substring(1, space < 0 ? line.length() : space);
        final String arguments = space < 0 ? "" : line.substring(space + 1).trim();
        final CommandDefinition command = byName.get(name);
        if(command == null) {
            return null;
        }
        final boolean wantsArguments = command.getTemplate().contains(ARGUMENTS_PLACEHOLDER);
        if(arguments.isEmpty() && wantsArguments) {
            return null;
        }
        String prompt = command.getTemplate();
        if(wantsArguments) {
            prompt = prompt.replace(ARGUMENTS_PLACEHOLDER, arguments);
        } else if(!arguments.isEmpty()) {
            prompt = prompt + "\n" + arguments;
        }
        return new Invocation(command, prompt);
    }

    /**
     * @return the catalog in registration order, for the chat-page hints
     */
    public List<CommandDefinition> catalog() {
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }
}
