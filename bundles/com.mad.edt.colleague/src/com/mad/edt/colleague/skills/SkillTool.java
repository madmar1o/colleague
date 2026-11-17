package com.mad.edt.colleague.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Advertised to the model as load_skill: hands over the full instruction
 * body of one skill plus its supporting file list. Until the model calls
 * it, only the short catalog line is in the prompt, so the bodies of skills
 * the task never touches cost nothing.
 */
public final class SkillTool implements IToolExecutor {

    /** Tool id the skill loader is addressed by. */
    public static final String TOOL_ID = "load_skill";

    /** Budget of supporting file names listed per skill. */
    private static final int FILE_LIST_BUDGET = 10;

    private final SkillRegistry registry;

    /**
     * @param theRegistry catalog the ids are looked up in
     */
    public SkillTool(final SkillRegistry theRegistry) {
        registry = theRegistry;
    }

    @Override
    public boolean isParallelSafe() {
        return true;
    }

    @Override
    public String getName() {
        return TOOL_ID;
    }

    /** @return tool declaration named load_skill with one string parameter id */
    @Override
    public ToolSpec getSpec() {
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        final JsonObject properties = new JsonObject();
        final JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description",
            "skill name exactly as shown in the available_skills catalog");
        properties.add("id", id);
        schema.add("properties", properties);
        final JsonArray required = new JsonArray();
        required.add("id");
        schema.add("required", required);
        return new ToolSpec("load_skill",
            "Fetch the full instructions of one skill by name plus its supporting"
                + " file list. Call this before following any skill listed in"
                + " available_skills.", schema);
    }

    @Override
    public String execute(final String theArgumentsJson) {
        final JsonObject args = JsonParser.parseString(theArgumentsJson).getAsJsonObject();
        final String id = args.get("id") == null ? "" : args.get("id").getAsString();
        return bodyFor(id);
    }

    /**
     * Executes one load_skill call.
     *
     * @param theId skill name from the catalog
     * @return body wrapped in skill_content plus sampled file list, or an
     *         ERROR line naming the catalog when the id is unknown
     */
    public String bodyFor(final String theId) {
        final SkillDefinition skill = registry.get(theId);
        if(skill == null) {
            final StringBuilder names = new StringBuilder();
            for(final SkillDefinition candidate : registry.getAll()) {
                if(candidate.isAutoInvoke()) {
                    names.append(names.length() == 0 ? "" : ", ").append(candidate.getName());
                }
            }
            return "ERROR: unknown skill id '" + theId + "'. available skills: " + names;
        }
        return "<skill_content name=\"" + skill.getName() + "\">\n" + skill.getBody()
            + "\n</skill_content>" + sampleFiles(skill);
    }

    /**
     * Lists up to {@value #FILE_LIST_BUDGET} file names sitting next to the
     * skill instructions (its own instruction file excluded), so the model
     * learns what else exists without paying for a full directory walk.
     *
     * @param theSkill skill whose directory is sampled
     * @return appended text starting with a newline, empty when the skill
     *         carries no directory or no extra files
     */
    private static String sampleFiles(final SkillDefinition theSkill) {
        if(theSkill.getSourceDir() == null || !Files.isDirectory(theSkill.getSourceDir())) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        int hidden = 0;
        try (Stream<java.nio.file.Path> walk = Files.list(theSkill.getSourceDir())) {
            final List<java.nio.file.Path> entries = walk
                .filter(Files::isRegularFile).sorted().toList();
            for(final java.nio.file.Path entry : entries) {
                final String fileName = entry.getFileName().toString();
                if("SKILL.md".equals(fileName)) {
                    continue;
                }
                if(names.size() < FILE_LIST_BUDGET) {
                    names.add(fileName);
                } else {
                    hidden++;
                }
            }
        } catch (final IOException ex) {
            return "\nskill files: listing failed: " + ex.getMessage();
        }
        if(names.isEmpty()) {
            return "";
        }
        final StringBuilder listed = new StringBuilder("\nskill files: ")
            .append(String.join(", ", names));
        if(hidden > 0) {
            listed.append(" (+").append(hidden).append(" more file(s))");
        }
        return listed.toString();
    }
}
