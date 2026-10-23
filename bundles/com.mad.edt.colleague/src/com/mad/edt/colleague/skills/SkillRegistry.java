package com.mad.edt.colleague.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory catalog of loaded skills.
 */
public final class SkillRegistry {

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /**
     * Adds or replaces a skill.
     *
     * @param theSkill the skill
     */
    public void register(final SkillDefinition theSkill) {
        skills.put(theSkill.getName(), theSkill);
    }

    /**
     * @param theName skill name
     * @return skill or {@code null}
     */
    public SkillDefinition get(final String theName) {
        return skills.get(theName);
    }

    /**
     * @return all skills in registration order
     */
    public List<SkillDefinition> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(skills.values()));
    }

    /**
     * @return {@code true} when no skills are registered
     */
    public boolean isEmpty() {
        return skills.isEmpty();
    }

    /**
     * Renders the skills as a system-prompt section: one block per skill with
     * its name, summary and full instructions.
     *
     * @return prompt text, empty string when no skills are registered
     */
    public String toPromptSection() {
        final StringBuilder builder = new StringBuilder();
        for(final SkillDefinition skill : skills.values()) {
            builder.append("## Skill: ").append(skill.getName()).append('\n');
            if(!skill.getDescription().isEmpty()) {
                builder.append(skill.getDescription()).append('\n');
            }
            builder.append(skill.getBody()).append("\n\n");
        }
        return builder.toString();
    }
}
