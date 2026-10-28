package com.mad.edt.colleague.skills;

/**
 * Generates the available_skills block of the system prompt: one line per
 * model-invocable skill with its name and one-line summary, never its full
 * body. The catalog is what the model reads first; the body of one skill
 * arrives only after the model calls load_skill for it, so the prompt cost
 * of the skill layer scales with the number of skills, not with the length
 * of their instructions.
 */
public final class SkillShowcase {

    private final SkillRegistry registry;

    /**
     * @param theRegistry loaded skills to advertise
     */
    public SkillShowcase(final SkillRegistry theRegistry) {
        registry = theRegistry;
    }

    /**
     * @return the catalog block ending in a newline, empty string when no
     *         skill is open to model invocation
     */
    public String catalogSection() {
        final StringBuilder catalog = new StringBuilder();
        for(final SkillDefinition skill : registry.getAll()) {
            if(!skill.isAutoInvoke()) {
                continue;
            }
            catalog.append("- ").append(skill.getName());
            if(!skill.getDescription().isEmpty()) {
                catalog.append(": ").append(skill.getDescription());
            }
            catalog.append('\n');
        }
        if(catalog.length() == 0) {
            return "";
        }
        return "\n## available_skills\n" + catalog
            + "When a listed skill fits the task, call load_skill with its name to fetch"
            + " the full instructions before following them.\n";
    }
}
