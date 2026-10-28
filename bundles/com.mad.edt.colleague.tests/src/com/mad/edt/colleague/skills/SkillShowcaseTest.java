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

import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Context economy of the skill layer: the prompt carries only a short
 * catalog of available skills (name and description per line), never their
 * full instruction bodies. The body reaches the model only when it asks for
 * one skill by id through the load_skill tool, which also samples the
 * skill's supporting file list. Skills marked as not model-invocable stay
 * out of the catalog altogether.
 */
class SkillShowcaseTest {

    /** marker text that must never leak from a body into the catalog */
    private static final String BODY_MARKER = "STEP BY STEP BODY MARKER";

    private static SkillRegistry registryOf(final SkillDefinition... theSkills) {
        final SkillRegistry registry = new SkillRegistry();
        for(final SkillDefinition skill : theSkills) {
            registry.register(skill);
        }
        return registry;
    }

    @Test
    @DisplayName("the catalog block carries name and description, never the body")
    void catalogGoesToSystemPromptNotBodies() {
        final SkillRegistry registry = registryOf(new SkillDefinition("reporter",
            "prints workspace reports", BODY_MARKER + " with the full instruction text",
            List.of()));

        final String catalog = new SkillShowcase(registry).catalogSection();

        Assertions.assertTrue(catalog.contains("available_skills"),
            "the block must name itself so the model knows what it reads");
        Assertions.assertTrue(catalog.contains("reporter"), catalog);
        Assertions.assertTrue(catalog.contains("prints workspace reports"), catalog);
        Assertions.assertTrue(catalog.contains("load_skill"),
            "the catalog must tell the model how to pick a skill up");
        Assertions.assertFalse(catalog.contains(BODY_MARKER),
            "the full body must not ride in the catalog block: " + catalog);
    }

    @Test
    @DisplayName("a skill closed to model invocation stays out of the catalog")
    void notModelInvocableSkillsAreHidden() {
        final SkillDefinition hidden = new SkillDefinition("manual-only", "user calls it",
            "body", List.of(), null, false);
        final SkillRegistry registry = registryOf(hidden, new SkillDefinition("open",
            "model may pick it", "body", List.of(), null, true));

        final String catalog = new SkillShowcase(registry).catalogSection();

        Assertions.assertFalse(catalog.contains("manual-only"), catalog);
        Assertions.assertTrue(catalog.contains("open"), catalog);
    }

    @Test
    @DisplayName("load_skill hands over the body plus a sampled file list")
    void loadSkillToolReturnsBody(@TempDir final Path theRoot) throws Exception {
        final Path skillDir = Files.createDirectories(theRoot.resolve("reporter"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: reporter\ndescription:"
            + " prints workspace reports\n---\n" + BODY_MARKER + "\n", StandardCharsets.UTF_8);
        for(int file = 1; file <= 12; file++) {
            Files.writeString(skillDir.resolve("ref" + file + ".md"), "reference",
                StandardCharsets.UTF_8);
        }
        final SkillRegistry registry = new SkillRegistry();
        new SkillLoader().loadAll(theRoot.toFile())
            .forEach(registry::register);

        final SkillTool tool = new SkillTool(registry);
        final ToolSpec spec = tool.getSpec();
        Assertions.assertEquals("load_skill", tool.getName());
        Assertions.assertEquals("load_skill", spec.getName());
        Assertions.assertTrue(spec.getParametersSchema().toString().contains("\"id\""),
            "the declaration must ask for the skill id");

        final String result = tool.execute("{\"id\":\"reporter\"}");

        Assertions.assertTrue(result.contains("<skill_content")
                && result.contains("</skill_content>"), result);
        Assertions.assertTrue(result.contains(BODY_MARKER),
            "the body arrives only after the model asked for it");
        Assertions.assertTrue(result.contains("ref"), result);
        Assertions.assertTrue(result.contains("more file(s)"),
            "a long file list must be sampled, not dumped: " + result);
        final int listedNames =
            (result.length() - result.replace(".md", "").length()) / ".md".length();
        Assertions.assertTrue(listedNames <= 10,
            "at most ten file names may be listed, got " + listedNames);
    }

    @Test
    @DisplayName("an unknown load_skill id answers with a usable error, not a crash")
    void unknownSkillIdIsReported() throws Exception {
        final SkillTool tool = new SkillTool(registryOf(new SkillDefinition("known",
            "d", "b", List.of())));

        final String result = tool.execute("{\"id\":\"absent\"}");

        Assertions.assertTrue(result.startsWith("ERROR"), result);
        Assertions.assertTrue(result.contains("known"),
            "the error should show what is available: " + result);
    }

    @Test
    @DisplayName("disable-model-invocation in the header closes the skill to the catalog")
    void loaderReadsModelInvocationFlag(@TempDir final Path theRoot) throws IOException {
        final File skillDir = theRoot.resolve("quiet").toFile();
        Assertions.assertTrue(skillDir.mkdirs());
        Files.writeString(new File(skillDir, "SKILL.md").toPath(),
            "---\nname: quiet\ndescription: hidden from the model\n"
                + "disable-model-invocation: true\n---\nbody\n", StandardCharsets.UTF_8);

        final List<SkillDefinition> loaded = new SkillLoader().loadAll(theRoot.toFile());

        Assertions.assertEquals(1, loaded.size());
        Assertions.assertFalse(loaded.get(0).isAutoInvoke(),
            "the header flag must survive into the definition");
    }
}
