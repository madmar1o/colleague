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
 * Agent profiles live on two levels: a personal catalogue the user keeps for
 * every workspace, and a project catalogue versioned with the project. The
 * merged view the UI shows is one list: a project profile replaces the personal
 * one of the same id, everything else stays, and the order is by name so the
 * pages can render the list without sorting it themselves.
 */
class AgentCatalogTest {

    private static final String PERSONAL = "personal";

    @Test
    @DisplayName("a project profile replaces the personal profile of the same id")
    void projectLevelOverridesPersonalById(@TempDir final Path theRoot) throws IOException {
        final Path personal = Files.createDirectories(theRoot.resolve(PERSONAL));
        final Path project = Files.createDirectories(theRoot.resolve("project"));
        Files.writeString(personal.resolve("reviewer.md"),
            "---\ndescription: personal take\n---\nPersonal prompt.\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("reviewer.md"),
            "---\ndescription: project take\n---\nProject prompt.\n", StandardCharsets.UTF_8);

        final List<AgentProfile> merged =
            AgentCatalog.load(personal.toFile(), project.toFile());

        Assertions.assertEquals(1, merged.size(),
            "one id means one entry, the project one wins");
        Assertions.assertEquals("project take", merged.get(0).getDescription());
        Assertions.assertEquals("Project prompt.", merged.get(0).getSystem().trim());
    }

    @Test
    @DisplayName("both levels merge into one list sorted by name")
    void bothLevelsMergeSorted(@TempDir final Path theRoot) throws IOException {
        final Path personal = Files.createDirectories(theRoot.resolve(PERSONAL));
        final Path project = Files.createDirectories(theRoot.resolve("project"));
        Files.writeString(personal.resolve("zebra.md"),
            "---\ndescription: personal zebra\n---\n", StandardCharsets.UTF_8);
        Files.writeString(personal.resolve("shared.md"),
            "---\ndescription: personal shared\n---\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("alpha.md"),
            "---\ndescription: project alpha\n---\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("shared.md"),
            "---\ndescription: project shared\n---\n", StandardCharsets.UTF_8);

        final List<AgentProfile> merged =
            AgentCatalog.load(personal.toFile(), project.toFile());

        Assertions.assertEquals(
            List.of("alpha", "shared", "zebra"),
            merged.stream().map(AgentProfile::getName).toList());
        Assertions.assertEquals("project shared",
            merged.get(1).getDescription());
    }

    @Test
    @DisplayName("a missing level is not an error: the other one alone is the catalogue")
    void missingLevelsAreEmpty(@TempDir final Path theRoot) throws IOException {
        final Path personal = Files.createDirectories(theRoot.resolve(PERSONAL));
        Files.writeString(personal.resolve("solo.md"),
            "---\ndescription: personal only\n---\n", StandardCharsets.UTF_8);
        final Path absent = theRoot.resolve("no-such-dir");

        Assertions.assertEquals(List.of("solo"),
            AgentCatalog.load(personal.toFile(), absent.toFile()).stream()
                .map(AgentProfile::getName).toList());
        Assertions.assertEquals(List.of("solo"),
            AgentCatalog.load(null, personal.toFile()).stream()
                .map(AgentProfile::getName).toList());
        Assertions.assertTrue(AgentCatalog.load(null, null).isEmpty());
    }
}
