package com.mad.edt.colleague.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The slash line is cut into the command name and its arguments by the
 * first whitespace: command names are whatever the project files are named,
 * including names in the user's own alphabet. Anything that does not name a
 * loaded command stays plain chat text - a mistyped slash must never be
 * silently rewritten.
 */
class SlashCommandParseTest {

    private static SlashCommands catalogOf(final CommandDefinition... theCommands) {
        return new SlashCommands(List.of(theCommands));
    }

    private static CommandDefinition command(final String theName, final String theTemplate) {
        return new CommandDefinition(theName, "d", theTemplate, null, false);
    }

    @Test
    @DisplayName("name and arguments split at the first whitespace, cyrillic names work")
    void splitsNameAndArgs() {
        final SlashCommands catalog = catalogOf(
            command("перевод", "Переведи: $ARGUMENTS"),
            command("plain", "no placeholder here"));

        final SlashCommands.Invocation cyrillic = catalog.match("/перевод   одной фразы на english");
        Assertions.assertNotNull(cyrillic);
        Assertions.assertEquals("Переведи: одной фразы на english", cyrillic.prompt(),
            "the rest of the line fills the placeholder, extra spaces collapse");

        final SlashCommands.Invocation noPlaceholder = catalog.match("/plain tail text");
        Assertions.assertNotNull(noPlaceholder);
        Assertions.assertEquals("no placeholder here\ntail text", noPlaceholder.prompt(),
            "without a placeholder the arguments join the template as a tail");
    }

    @Test
    @DisplayName("unknown slash lines and plain text stay untouched")
    void unknownSlashAndPlainTextAreNoInvocation() {
        final SlashCommands catalog = catalogOf(command("review", "r $ARGUMENTS"));

        Assertions.assertNull(catalog.match("/nope something"));
        Assertions.assertNull(catalog.match("please /review this inside text"));
        Assertions.assertNull(catalog.match("/review"));
        Assertions.assertNull(catalog.match(""));
        Assertions.assertNull(catalog.match(null));
    }

    @Test
    @DisplayName("a bare command name still invokes with empty arguments")
    void bareNameInvokesWhenTemplateNeedsNoArgs() {
        final SlashCommands catalog = catalogOf(command("standup", "List yesterday."));

        final SlashCommands.Invocation call = catalog.match("/standup");

        Assertions.assertNotNull(call);
        Assertions.assertEquals("List yesterday.", call.prompt());
    }
}
