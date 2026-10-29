package com.mad.edt.colleague.skills;

import java.nio.file.Path;
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
    private final Path sourceDir;
    private final boolean autoInvoke;

    /**
     * @param theName unique skill name
     * @param theDescription one-line summary shown to the model
     * @param theBody instruction text of the skill
     * @param theTools tools contributed by the skill
     */
    public SkillDefinition(final String theName, final String theDescription, final String theBody,
        final List<ToolSpec> theTools) {
        this(theName, theDescription, theBody, theTools, null, true);
    }

    /**
     * @param theName unique skill name
     * @param theDescription one-line summary shown to the model
     * @param theBody instruction text of the skill
     * @param theTools tools contributed by the skill
     * @param theSourceDir directory the skill file lives in, {@code null}
     *            when the skill came from elsewhere; used to sample the
     *            supporting file list for load_skill
     * @param theAutoInvoke {@code false} when the header closed the skill to
     *            model invocation: it stays out of the showcase catalog
     */
    public SkillDefinition(final String theName, final String theDescription, final String theBody,
        final List<ToolSpec> theTools, final Path theSourceDir, final boolean theAutoInvoke) {
        name = theName;
        description = theDescription == null ? "" : theDescription;
        body = theBody == null ? "" : theBody;
        tools = Collections.unmodifiableList(new ArrayList<>(theTools));
        sourceDir = theSourceDir;
        autoInvoke = theAutoInvoke;
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

    /**
     * @return directory the skill file lives in, {@code null} when the skill
     *         came from elsewhere
     */
    public Path getSourceDir() {
        return sourceDir;
    }

    /**
     * @return whether the model may pick this skill up from the catalog;
     *         {@code false} for skills the header closed to model invocation
     */
    public boolean isAutoInvoke() {
        return autoInvoke;
    }
}
