package com.mad.edt.colleague.agents;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user-facing agent is a profile file the page can create, correct and
 * remove. Writing must round-trip through the loader (what the page saves is
 * what the chat runs), hostile characters in one-line fields must not be able
 * to forge extra header lines, and a save that lands must never leave a half
 * written file behind.
 */
class AgentProfileWriterTest {

    @Test
    @DisplayName("what the writer saves is exactly what the loader reads back")
    void saveRoundTripsThroughTheLoader(@TempDir final Path theRoot) throws IOException {
        final AgentProfile profile = new AgentProfile("reviewer", "разбор изменений",
            "Смотри diff и называй риски.\nВторой абзац.", "big/model:7", 4,
            AgentProfile.Mode.PRIMARY, Set.of("read_file", "search_code"), false,
            "Ревьюер");

        AgentProfileWriter.save(theRoot.toFile(), profile);

        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());
        Assertions.assertEquals(1, loaded.size());
        final AgentProfile back = loaded.get(0);
        Assertions.assertEquals("reviewer", back.getName());
        Assertions.assertEquals("Ревьюер", back.getDisplayName());
        Assertions.assertEquals("разбор изменений", back.getDescription());
        Assertions.assertEquals("big/model:7", back.getModelName());
        Assertions.assertEquals(4, back.getMaxSteps());
        Assertions.assertEquals(AgentProfile.Mode.PRIMARY, back.getMode());
        Assertions.assertEquals(Set.of("read_file", "search_code"), back.getToolNames());
        Assertions.assertEquals("Смотри diff и называй риски.\nВторой абзац.",
            back.getSystem().trim());
    }

    @Test
    @DisplayName("one-line fields cannot forge extra header lines")
    void hostileValuesAreFlattenedToOneLine(@TempDir final Path theRoot) throws IOException {
        final AgentProfile profile = new AgentProfile("quiet",
            "первая строка\nhidden: true\nmode: subagent", "system", null, 0,
            AgentProfile.Mode.ALL, Set.of(), false, "");

        AgentProfileWriter.save(theRoot.toFile(), profile);

        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());
        Assertions.assertEquals(1, loaded.size());
        final AgentProfile back = loaded.get(0);
        Assertions.assertFalse(back.isHidden(),
            "a newline in the description must not open a hidden header line");
        Assertions.assertEquals(AgentProfile.Mode.ALL, back.getMode());
        Assertions.assertEquals("первая строка hidden: true mode: subagent",
            back.getDescription());
    }

    @Test
    @DisplayName("an id that is not a safe file name is refused")
    void unsafeIdsAreRefused(@TempDir final Path theRoot) {
        for(final String bad : List.of("", "Bad Name", "two words", "a/b", "..\\evil",
            "x".repeat(65))) {
            final AgentProfile profile = new AgentProfile(bad, "d", "s", null, 0,
                AgentProfile.Mode.ALL, Set.of(), false, "");
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentProfileWriter.save(theRoot.toFile(), profile),
                "id [" + bad + "] must not reach the file system");
        }
    }

    @Test
    @DisplayName("saving the same id again corrects the file in place")
    void saveTwiceUpdatesInPlace(@TempDir final Path theRoot) throws IOException {
        AgentProfileWriter.save(theRoot.toFile(), new AgentProfile("reviewer",
            "первая версия", "system", null, 0, AgentProfile.Mode.ALL, Set.of(), false, ""));
        AgentProfileWriter.save(theRoot.toFile(), new AgentProfile("reviewer",
            "вторая версия", "system", null, 0, AgentProfile.Mode.ALL, Set.of(), false, ""));

        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());
        Assertions.assertEquals(1, loaded.size(), "one id is one file");
        Assertions.assertEquals("вторая версия", loaded.get(0).getDescription());
        Assertions.assertTrue(AgentProfileWriter.exists(theRoot.toFile(), "reviewer"));
    }

    @Test
    @DisplayName("deleting an agent removes its file and only its file")
    void deleteRemovesOnlyThatProfile(@TempDir final Path theRoot) throws IOException {
        AgentProfileWriter.save(theRoot.toFile(), new AgentProfile("first",
            "d one", "s", null, 0, AgentProfile.Mode.ALL, Set.of(), false, ""));
        AgentProfileWriter.save(theRoot.toFile(), new AgentProfile("second",
            "d two", "s", null, 0, AgentProfile.Mode.ALL, Set.of(), false, ""));

        Assertions.assertTrue(AgentProfileWriter.delete(theRoot.toFile(), "first"));
        Assertions.assertFalse(AgentProfileWriter.delete(theRoot.toFile(), "first"),
            "a second delete finds nothing to remove");

        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());
        Assertions.assertEquals(List.of("second"),
            loaded.stream().map(AgentProfile::getName).toList());
    }

    @Test
    @DisplayName("a ready profile text lands as a file the loader reads back")
    void readyTextLandsAsAReadableProfile(@TempDir final Path theRoot) throws IOException {
        final String text = "---\r\ntitle: Аналитик 1С\r\ndescription: разбор типовых\r\n"
            + "mode: primary\r\n---\r\nТы — аналитик 1С.\r\n";

        final File written = AgentProfileWriter.saveText(theRoot.toFile(), "analyst-1c", text);

        Assertions.assertEquals("analyst-1c.md", written.getName());
        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());
        Assertions.assertEquals(1, loaded.size());
        Assertions.assertEquals("Аналитик 1С", loaded.get(0).getDisplayName());
        Assertions.assertEquals(AgentProfile.Mode.PRIMARY, loaded.get(0).getMode());
        // the text is stored as it was handed over, so what the seed ships is
        // what the user finds in the file and can correct by hand
        Assertions.assertEquals(text, Files.readString(written.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ready text is refused for an id that is not a safe file name")
    void readyTextRefusesUnsafeIds(@TempDir final Path theRoot) throws IOException {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> AgentProfileWriter.saveText(theRoot.toFile(), "../escape", "x"));
        try (java.util.stream.Stream<Path> listing = Files.list(theRoot)) {
            Assertions.assertTrue(listing.findAny().isEmpty(),
                "nothing may reach the directory through an unsafe id");
        }
    }

    @Test
    @DisplayName("the tools header is written in one stable order")
    void toolsHeaderIsStable(@TempDir final Path theRoot) throws IOException {
        final AgentProfile profile = new AgentProfile("steady", "d", "s", null, 0,
            AgentProfile.Mode.ALL, Set.of("write_file", "read_file", "search_code"), false, "");

        Assertions.assertEquals(List.of("read_file", "search_code", "write_file"),
            profile.getToolNamesInOrder(),
            "the page form and the file must agree on one order");

        final File written = AgentProfileWriter.save(theRoot.toFile(), profile);

        final String text = Files.readString(written.toPath(), StandardCharsets.UTF_8);
        Assertions.assertTrue(text.contains("tools: read_file, search_code, write_file"),
            "saving the same profile twice must not shuffle its own file, got: " + text);
    }
}
