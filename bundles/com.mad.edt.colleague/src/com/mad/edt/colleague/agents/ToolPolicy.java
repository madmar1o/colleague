package com.mad.edt.colleague.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Decides which tools a profile may see. Read-only tools are always allowed;
 * write tools additionally require the edit mode, exactly as today. A profile
 * narrows the advertised set but never widens write access.
 */
public final class ToolPolicy {

    /** Tools that change project state; the workspace names them this way. */
    public static final Set<String> WRITE_TOOLS = Collections.unmodifiableSet(
        new LinkedHashSet<>(List.of("write_file", "edit_file")));

    private final Set<String> writeToolNames;

    /**
     * @param theWriteToolNames names treated as writers; the filter refuses to
     *            offer them beyond what the workspace already offered
     */
    public ToolPolicy(final Set<String> theWriteToolNames) {
        writeToolNames = Collections.unmodifiableSet(new LinkedHashSet<>(theWriteToolNames));
    }

    /**
     * Policy for the tool names the workspace actually uses.
     */
    public ToolPolicy() {
        this(WRITE_TOOLS);
    }

    /**
     * Filters the advertised tool list for one profile.
     *
     * @param theProfile profile to apply, null means the default agent
     * @param theAll full tool list currently offered
     * @return the filtered list, order preserved
     */
    public List<ToolSpec> allowed(final AgentProfile theProfile, final List<ToolSpec> theAll) {
        final List<ToolSpec> kept = new ArrayList<>();
        for(final ToolSpec spec : theAll) {
            if(allows(theProfile, spec.getName())) {
                kept.add(spec);
            }
        }
        return Collections.unmodifiableList(kept);
    }

    /**
     * Decides one tool by name. A profile with no tool list narrows nothing,
     * otherwise only the names it listed survive - and a name the workspace
     * did not offer today cannot appear through the profile either, which is
     * what keeps write access tied to the edit mode.
     *
     * @param theProfile profile to apply, {@code null} means the default agent
     * @param theToolName tool name to decide
     * @return whether the model may be offered this tool
     */
    public boolean allows(final AgentProfile theProfile, final String theToolName) {
        if(theProfile == null || theProfile.getToolNames().isEmpty()) {
            return true;
        }
        return theProfile.getToolNames().contains(theToolName);
    }

    /**
     * @param theToolName tool name to classify
     * @return whether calling this tool changes project state
     */
    public boolean isWriteTool(final String theToolName) {
        return writeToolNames.contains(theToolName);
    }
}
