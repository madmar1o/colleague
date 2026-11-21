package com.mad.edt.colleague.ext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One frontmatter parser for every markdown-defined extension (skills,
 * slash commands, agent profiles): the header is {@code key: value} lines
 * between {@code ---} fences, so a value may itself contain colons, and the
 * raw file text arrives with whatever line endings and BOM the editor left
 * behind. The reload registry behind them must keep registration order and
 * must never lose the last good catalog when a rebuild fails.
 */
class FrontmatterParserTest {

    private static final String ALPHA = "alpha";

    @Test
    @DisplayName("a header value keeps its own colons")
    void parsesKeyColonValueWithColonInValue() {
        final Frontmatter parsed = Frontmatter.split(
            "---\nname: greeter\ndescription: use: carefully around edges\n---\nBody text\n");

        Assertions.assertEquals("greeter", parsed.fields().get("name"));
        Assertions.assertEquals("use: carefully around edges",
            parsed.fields().get("description"),
            "only the first colon separates key from value");
        Assertions.assertEquals("Body text", parsed.body());
    }

    @Test
    @DisplayName("BOM and CRLF line endings are invisible to the parser")
    void ignoresBomAndCrlf() {
        final Frontmatter parsed = Frontmatter.split(
            "\uFEFF---\r\nname: greeter\r\ndescription: says hi\r\n---\r\nLine one\r\nLine two\r\n");

        Assertions.assertEquals("greeter", parsed.fields().get("name"),
            "the BOM must not hide the opening fence");
        Assertions.assertEquals("says hi", parsed.fields().get("description"));
        Assertions.assertEquals("Line one\nLine two", parsed.body());
    }

    @Test
    @DisplayName("a file without a header is pure body")
    void fileWithoutHeaderKeepsWholeTextAsBody() {
        final Frontmatter parsed = Frontmatter.split("just body: colons stay here");

        Assertions.assertTrue(parsed.fields().isEmpty());
        Assertions.assertEquals("just body: colons stay here", parsed.body());
    }

    @Test
    @DisplayName("the registry keeps insertion order and answers by name")
    void registryKeepsOrderAndAnswersLookups() {
        final ReloadableRegistry<String> registry = new ReloadableRegistry<>();
        final Map<String, String> loaded = new LinkedHashMap<>();
        loaded.put("zeta", "last written");
        loaded.put(ALPHA, "first written");
        registry.reload(loaded);

        Assertions.assertEquals(List.of("zeta", ALPHA),
            new ArrayList<>(registry.snapshot().keySet()),
            "registration order is the display order of the catalog");
        Assertions.assertEquals("first written", registry.get(ALPHA));
        Assertions.assertNull(registry.get("absent"));
    }

    @Test
    @DisplayName("a rebuild that throws leaves the previous catalog live")
    void failedRebuildKeepsThePreviousSnapshot() {
        final ReloadableRegistry<String> registry = new ReloadableRegistry<>();
        registry.reload(Map.of("good", "entry"));

        Assertions.assertThrows(IllegalStateException.class, () -> registry.reload(
            current -> {
                final Map<String, String> half = new LinkedHashMap<>(current);
                half.put("broken", "dangling");
                throw new IllegalStateException("source died mid-rebuild");
            }));

        Assertions.assertEquals("entry", registry.get("good"));
        Assertions.assertNull(registry.get("broken"),
            "a partial rebuild must never leak into the live catalog");
    }

    @Test
    @DisplayName("a successful rebuild replaces the whole catalog")
    void successfulRebuildReplacesEverything() {
        final ReloadableRegistry<String> registry = new ReloadableRegistry<>();
        registry.reload(Map.of("old", "gone"));

        registry.reload(current -> {
            final Map<String, String> fresh = new LinkedHashMap<>();
            fresh.put("new", "kept");
            return fresh;
        });

        Assertions.assertNull(registry.get("old"));
        Assertions.assertEquals("kept", registry.get("new"));
    }
}
