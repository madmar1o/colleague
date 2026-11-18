package com.mad.edt.colleague.agents;

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
 * An agent profile is a markdown file in the project: the header carries what
 * the agent is for, which model and how many steps it may take, and which
 * tools it may see; the body is the system prompt. The header is read by the
 * shared frontmatter parser, the same one skills and commands use, so all
 * three formats stay one parser deep.
 */
class AgentProfileLoaderTest {

    // java:S6126 kept suppressed: the expected value has no trailing newline,
    // a text block would append one and change the byte-exact assertion.
    @SuppressWarnings("java:S6126")
    @Test
    void parsesToolListAndSteps(@TempDir final Path theRoot) throws IOException {
        Files.writeString(theRoot.resolve("reviewer.md"),
            "---\r\ndescription: review only\r\nmodel: big/model:7\r\n"
                + "max-steps: 4\r\nmode: primary\r\n"
                + "tools: read_file, search_code\r\nhidden: true\r\n---\r\n"
                + "Read the change and name the risks.\r\n",
            StandardCharsets.UTF_8);

        final List<AgentProfile> loaded = new AgentProfileLoader().loadAll(theRoot.toFile());

        Assertions.assertEquals(1, loaded.size());
        final AgentProfile profile = loaded.get(0);
        Assertions.assertEquals("reviewer", profile.getName());
        Assertions.assertEquals("review only", profile.getDescription());
        Assertions.assertEquals("big/model:7", profile.getModelName());
        Assertions.assertEquals(4, profile.getMaxSteps());
        Assertions.assertEquals(AgentProfile.Mode.PRIMARY, profile.getMode());
        Assertions.assertTrue(profile.getToolNames().contains("read_file"),
            String.valueOf(profile.getToolNames()));
        Assertions.assertTrue(profile.getToolNames().contains("search_code"),
            String.valueOf(profile.getToolNames()));
        Assertions.assertTrue(profile.isHidden());
        Assertions.assertEquals("Read the change and name the risks.", profile.getSystem());
    }

    @Test
    @DisplayName("an absent header field falls back to the open default")
    void absentFieldsLeaveTheProfileOpen(@TempDir final Path theRoot) throws IOException {
        Files.writeString(theRoot.resolve("plain.md"),
            "no header at all, just the system prompt", StandardCharsets.UTF_8);

        final AgentProfile profile = new AgentProfileLoader().loadAll(theRoot.toFile()).get(0);

        Assertions.assertEquals(AgentProfile.Mode.ALL, profile.getMode());
        Assertions.assertTrue(profile.getToolNames().isEmpty(),
            "no tool list means the profile narrows nothing");
        Assertions.assertEquals(0, profile.getMaxSteps());
        Assertions.assertNull(profile.getModelName());
        Assertions.assertFalse(profile.isHidden());
        Assertions.assertEquals("no header at all, just the system prompt", profile.getSystem());
    }

    @Test
    @DisplayName("a missing profiles directory yields no profiles, not a failure")
    void missingDirectoryYieldsEmpty(@TempDir final Path theRoot) throws IOException {
        Assertions.assertTrue(new AgentProfileLoader()
            .loadAll(theRoot.resolve("no-such-dir").toFile()).isEmpty());
    }

    // java:S6126 kept suppressed: the fixture pins CRLF line endings,
    // a text block cannot express carriage returns.
    @SuppressWarnings("java:S6126")
    @Test
    @DisplayName("the title header is the display name, the file name stays the id")
    void titleIsTheDisplayNameFileNameStaysTheId(@TempDir final Path theRoot)
        throws IOException {
        Files.writeString(theRoot.resolve("analyst-1c.md"),
            "---\r\ntitle: Аналитик 1С\r\ndescription: разбор типовых\r\n---\r\n"
                + "Ты — аналитик 1С.\r\n",
            StandardCharsets.UTF_8);

        final AgentProfile profile = new AgentProfileLoader().loadAll(theRoot.toFile()).get(0);

        Assertions.assertEquals("analyst-1c", profile.getName(),
            "the id used for picking and file keeping stays the file name");
        Assertions.assertEquals("Аналитик 1С", profile.getDisplayName());
    }

    @Test
    @DisplayName("without a title header the display name is the file name")
    void noTitleFallsBackToFileName(@TempDir final Path theRoot) throws IOException {
        Files.writeString(theRoot.resolve("plain.md"),
            "---\ndescription: no title here\n---\n", StandardCharsets.UTF_8);

        final AgentProfile profile = new AgentProfileLoader().loadAll(theRoot.toFile()).get(0);

        Assertions.assertEquals("plain", profile.getDisplayName());
    }
}
