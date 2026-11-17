package com.mad.edt.colleague.skills;

import java.io.File;
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
 * Guards the skill directory contract while its header parsing moved onto
 * the shared frontmatter parser: the {@code ---} header supplies name and
 * description, an absent header falls back to the directory name, the body
 * below the fence is passed through stripped, and an optional
 * {@code tools.json} declares the skill's tools.
 */
class SkillLoaderTest {

    private static final String SKILL_FILE = "SKILL.md";

    private final SkillLoader loader = new SkillLoader();

    private static File skillDir(final File theRoot, final String theName) throws IOException {
        final File dir = new File(theRoot, theName);
        Assertions.assertTrue(dir.mkdirs());
        return dir;
    }

    private static void write(final File theFile, final String theText) throws IOException {
        Files.writeString(theFile.toPath(), theText, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the header supplies name and description, colons in values survive")
    void headerSuppliesNameDescriptionAndBody(@TempDir final Path theRootTemp) throws IOException {
        final File root = theRootTemp.toFile();
        final File dir = skillDir(root, "folder");
        write(new File(dir, SKILL_FILE),
            "---\nname: alpha\ndescription: use: carefully\n---\nDo things.\n");

        final List<SkillDefinition> skills = loader.loadAll(root);

        Assertions.assertEquals(1, skills.size());
        Assertions.assertEquals("alpha", skills.get(0).getName());
        Assertions.assertEquals("use: carefully", skills.get(0).getDescription());
        Assertions.assertEquals("Do things.", skills.get(0).getBody());
    }

    @Test
    @DisplayName("a file without a header keeps the directory name as its name")
    void missingHeaderFallsBackToDirectoryName(@TempDir final Path theRootTemp) throws IOException {
        final File root = theRootTemp.toFile();
        final File dir = skillDir(root, "beta-skill");
        write(new File(dir, SKILL_FILE), "plain instructions, no fence at all");

        final List<SkillDefinition> skills = loader.loadAll(root);

        Assertions.assertEquals("beta-skill", skills.get(0).getName());
        Assertions.assertEquals("", skills.get(0).getDescription());
        Assertions.assertEquals("plain instructions, no fence at all", skills.get(0).getBody());
    }

    @Test
    @DisplayName("an optional tools.json declares the skill tools")
    void toolsFileDeclaresToolSpecs(@TempDir final Path theRootTemp) throws IOException {
        final File root = theRootTemp.toFile();
        final File dir = skillDir(root, "gamma");
        write(new File(dir, SKILL_FILE), "---\nname: gamma\n---\nbody\n");
        write(new File(dir, "tools.json"), """
            [{"name": "lookup", "description": "looks something up",
              "parameters": {"type": "object"}}]
            """);

        final List<SkillDefinition> skills = loader.loadAll(root);

        Assertions.assertEquals(1, skills.get(0).getTools().size());
        Assertions.assertEquals("lookup", skills.get(0).getTools().get(0).getName());
    }

    @Test
    @DisplayName("loadAll sorts by name and ignores directories without SKILL.md")
    void loadAllSortsAndSkipsForeignDirectories(@TempDir final Path theRootTemp) throws IOException {
        final File root = theRootTemp.toFile();
        write(new File(skillDir(root, "zeta"), SKILL_FILE), "---\nname: zulu\n---\nb\n");
        skillDir(root, "notes-only");
        write(new File(skillDir(root, "alpha-dir"), SKILL_FILE), "---\nname: anchor\n---\nb\n");

        final List<SkillDefinition> skills = loader.loadAll(root);

        Assertions.assertEquals(List.of("anchor", "zulu"),
            skills.stream().map(SkillDefinition::getName).toList());
    }
}
