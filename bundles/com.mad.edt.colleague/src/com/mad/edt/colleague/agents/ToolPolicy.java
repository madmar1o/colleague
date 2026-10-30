package com.mad.edt.colleague.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mad.edt.colleague.agent.ChatMode;
import com.mad.edt.colleague.agent.ToolUsageStats;
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

    /** Idle-run threshold of the pruning stage, {@code <= 0} switches it off. */
    private int pruneAfterIdleRuns;

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

    /**
     * Decides one tool by name against the chat mode. The ask mode hands no
     * tool to the model at all; the plan mode keeps observers and the plan
     * board but refuses every writer, so a planning agent can look and lay
     * out steps yet physically cannot change anything; the execute mode
     * narrows nothing. The mode gate adds to the profile gate, it never
     * widens what a profile refused.
     *
     * @param theMode chat mode of the coming turn, {@code null} treated as
     *            {@link ChatMode#ASK}
     * @param theToolName tool name to decide
     * @return whether the mode may offer this tool to the model
     */
    public boolean allowsIn(final ChatMode theMode, final String theToolName) {
        if(theMode == ChatMode.PLAN) {
            return !isWriteTool(theToolName);
        }
        return theMode == ChatMode.EXECUTE;
    }

    /**
     * Adds the pruning stage: a tool the model has been offered for this many
     * finished runs without ever calling stops being offered. The counter
     * lives in {@link ToolUsageStats}, kept by the chat across its runs; a
     * call inside a run resets the streak there, so the tool returns to the
     * catalog by itself the moment the model starts using it again. A
     * threshold of {@code <= 0} switches pruning off, which is the state of a
     * freshly built policy.
     *
     * @param theRuns number of consecutive offered-but-unused finished runs
     *            that removes a tool from the catalog
     * @return this policy for one-line wiring
     */
    public ToolPolicy excludeUnused(final int theRuns) {
        pruneAfterIdleRuns = theRuns;
        return this;
    }

    /**
     * @param theUsage call counter kept by the chat, {@code null} switches
     *            pruning off for this decision
     * @param theToolName tool name to decide
     * @return whether the tool may still be advertised, false only when
     *         pruning is on and the tool has sat out the threshold of offered
     *         runs without a call
     */
    public boolean shouldOffer(final ToolUsageStats theUsage, final String theToolName) {
        return pruneAfterIdleRuns <= 0 || theUsage == null
            || theUsage.idleRunsOf(theToolName) < pruneAfterIdleRuns;
    }

    /**
     * Profile filtering plus usage pruning in one step: a tool has to please
     * both the profile and the usage counter to reach the model.
     *
     * @param theProfile profile to apply, {@code null} means the default agent
     * @param theAll full tool list currently offered
     * @param theUsage call counter, {@code null} switches pruning off
     * @return the filtered list, order preserved
     */
    public List<ToolSpec> allowed(final AgentProfile theProfile, final List<ToolSpec> theAll,
        final ToolUsageStats theUsage) {
        final List<ToolSpec> kept = new ArrayList<>();
        for(final ToolSpec spec : theAll) {
            if(allows(theProfile, spec.getName()) && shouldOffer(theUsage, spec.getName())) {
                kept.add(spec);
            }
        }
        return Collections.unmodifiableList(kept);
    }
}
