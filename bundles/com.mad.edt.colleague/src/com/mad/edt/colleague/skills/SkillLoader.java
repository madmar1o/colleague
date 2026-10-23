package com.mad.edt.colleague.skills;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Loads skills from a directory layout: {@code <skills>/<name>/SKILL.md} with
 * a {@code ---} delimited header (name, description) followed by the
 * instruction body, and an optional {@code tools.json} array of tool
 * declarations ({@code name}, {@code description}, {@code parameters}).
 */
public final class SkillLoader {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String TOOLS_FILE = "tools.json";

    /**
     * Loads every skill directory below {@code theSkillsDir}.
     *
     * @param theSkillsDir root directory, missing directory yields empty list
     * @return loaded skills, sorted by name
     * @throws IOException on file system failures
     */
    public List<SkillDefinition> loadAll(final File theSkillsDir) throws IOException {
        final List<SkillDefinition> skills = new ArrayList<>();
        if(theSkillsDir == null || !theSkillsDir.isDirectory()) {
            return skills;
        }
        final File[] dirs = theSkillsDir.listFiles(File::isDirectory);
        if(dirs == null) {
            return skills;
        }
        for(final File dir : dirs) {
            final File skillFile = new File(dir, SKILL_FILE);
            if(skillFile.isFile()) {
                skills.add(loadOne(skillFile, new File(dir, TOOLS_FILE)));
            }
        }
        skills.sort((left, right) -> left.getName().compareTo(right.getName()));
        return skills;
    }

    private SkillDefinition loadOne(final File theSkillFile, final File theToolsFile) throws IOException {
        final String raw = Files.readString(theSkillFile.toPath(), StandardCharsets.UTF_8);
        String name = theSkillFile.getParentFile().getName();
        String description = "";
        String body = raw;

        final String normalized = raw.replace("\r\n", "\n");
        if(normalized.startsWith("---\n")) {
            final int end = normalized.indexOf("\n---", 4);
            if(end > 0) {
                final String header = normalized.substring(4, end);
                body = normalized.substring(normalized.indexOf('\n', end + 1) + 1);
                for(final String line : header.split("\n")) {
                    final int colon = line.indexOf(':');
                    if(colon <= 0) {
                        continue;
                    }
                    final String key = line.substring(0, colon).trim();
                    final String value = line.substring(colon + 1).trim();
                    if("name".equals(key)) {
                        name = value;
                    } else if("description".equals(key)) {
                        description = value;
                    }
                }
            }
        }

        return new SkillDefinition(name, description, body.strip(), loadTools(theToolsFile));
    }

    private List<ToolSpec> loadTools(final File theToolsFile) throws IOException {
        final List<ToolSpec> specs = new ArrayList<>();
        if(!theToolsFile.isFile()) {
            return specs;
        }
        final String raw = Files.readString(theToolsFile.toPath(), StandardCharsets.UTF_8);
        final JsonElement parsed = JsonParser.parseString(raw);
        final JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        for(final JsonElement element : array) {
            final JsonObject tool = element.getAsJsonObject();
            final String name = text(tool, "name");
            if(name == null) {
                continue;
            }
            final JsonObject schema = tool.getAsJsonObject("parameters");
            specs.add(new ToolSpec(name, text(tool, "description"), schema));
        }
        return specs;
    }

    private static String text(final JsonObject theObject, final String theName) {
        final JsonElement element = theObject.get(theName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
