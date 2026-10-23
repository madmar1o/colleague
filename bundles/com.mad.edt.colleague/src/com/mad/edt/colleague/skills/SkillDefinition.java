package com.mad.edt.colleague.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mad.edt.colleague.llm.ToolSpec;

/**
 * One loaded skill: instructions plus the tools it makes callable.
 */
public final class SkillDefinition {

    private final String name;
    private final String description;
    private final String body;
    private final List<ToolSpec> tools;

    /**
     * @param theName unique skill name
     * @param theDescription one-line summary shown to the model
     * @param theBody instruction text of the skill
     * @param theTools tools contributed by the skill
     */
    public SkillDefinition(final String theName, final String theDescription, final String theBody,
        final List<ToolSpec> theTools) {
        name = theName;
        description = theDescription == null ? "" : theDescription;
        body = theBody == null ? "" : theBody;
        tools = Collections.unmodifiableList(new ArrayList<>(theTools));
    }

    /**
     * @return skill name
     */
    public String getName() {
        return name;
    }

    /**
     * @return one-line summary
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return full instruction text
     */
    public String getBody() {
        return body;
    }

    /**
     * @return tools contributed by the skill
     */
    public List<ToolSpec> getTools() {
        return tools;
    }
}
