package com.mad.edt.colleague.commands;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.mad.edt.colleague.ext.Frontmatter;

/**
 * Loads user commands from &lt;workspace&gt;/.colleague/commands/*.md:
 * a frontmatter header (description, optional agent and subagent flags)
 * over a template body where ARGUMENTS receives the rest of the
 * user line. The header is read by the shared {@link Frontmatter} parser,
 * the same one skills and profiles use.
 */
public final class CommandLoader {

    private static final String MARKDOWN_SUFFIX = ".md";

    /**
     * Loads every command file below the given directory.
     *
     * @param theCommandsDir root directory, missing directory yields empty list
     * @return loaded commands sorted by name
     * @throws IOException on file system failures
     */
    public List<CommandDefinition> loadAll(final File theCommandsDir) throws IOException {
        final List<CommandDefinition> commands = new ArrayList<>();
        if(theCommandsDir == null || !theCommandsDir.isDirectory()) {
            return commands;
        }
        final File[] files = theCommandsDir.listFiles(
            file -> file.isFile() && file.getName().endsWith(MARKDOWN_SUFFIX));
        if(files == null) {
            return commands;
        }
        for(final File file : files) {
            final String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            final Frontmatter header = Frontmatter.split(raw);
            final String fileName = file.getName();
            commands.add(new CommandDefinition(
                fileName.substring(0, fileName.length() - MARKDOWN_SUFFIX.length()),
                header.fields().getOrDefault("description", ""),
                header.body(),
                header.fields().getOrDefault("agent", "").isEmpty()
                    ? null : header.fields().get("agent"),
                "true".equalsIgnoreCase(header.fields().getOrDefault("subagent", "false"))));
        }
        commands.sort((left, right) -> left.getName().compareTo(right.getName()));
        return commands;
    }
}
