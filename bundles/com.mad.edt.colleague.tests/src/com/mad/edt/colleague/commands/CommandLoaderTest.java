package com.mad.edt.colleague.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User commands live as markdown files in the project: a frontmatter header
 * carries the one-line description (and the optional agent binding), the
 * body is a prompt template where the placeholder for the user's arguments
 * receives the rest of the slash line. A workspace without the commands
 * directory simply has no commands, it is not an error.
 */
class CommandLoaderTest {

    @Test
    @DisplayName("the template body carries the placeholder for user arguments")
    void parsesTemplateAndArguments(@TempDir final Path theRoot) throws IOException {
        Files.writeString(theRoot.resolve("review.md"),
            """
            ---
            description: review a change
            agent: fixer
            ---
            Review this change: $ARGUMENTS
            Name the risks first.
            """,
            StandardCharsets.UTF_8);

        final List<CommandDefinition> loaded = new CommandLoader().loadAll(theRoot.toFile());

        Assertions.assertEquals(1, loaded.size());
        final CommandDefinition command = loaded.get(0);
        Assertions.assertEquals("review", command.getName());
        Assertions.assertEquals("review a change", command.getDescription());
        Assertions.assertEquals("fixer", command.getAgentName());
        Assertions.assertTrue(command.getTemplate().contains("$ARGUMENTS"), command.getTemplate());

        final SlashCommands catalog = new SlashCommands(loaded);
        final SlashCommands.Invocation call = catalog.match("/review the payment module");
        Assertions.assertNotNull(call, "a known command name must be recognized");
        Assertions.assertEquals("review", call.command().getName());
        Assertions.assertEquals("Review this change: the payment module\nName the risks first.",
            call.prompt());
    }

    @Test
    @DisplayName("a missing commands directory yields no commands, not a failure")
    void missingFileYieldsEmpty(@TempDir final Path theRoot) throws IOException {
        final Path absent = theRoot.resolve("no-such-dir");

        Assertions.assertTrue(new CommandLoader().loadAll(absent.toFile()).isEmpty());
    }
}
