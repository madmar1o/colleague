package com.mad.edt.colleague.agents;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent catalog of a workspace across its two storage levels: a personal
 * directory the user keeps for every workspace, and a project directory under
 * {@code .colleague/agents} versioned with the project. The merged view is one
 * list: a project profile replaces the personal profile of the same id, all
 * other entries stay, and the result is sorted by name so every page renders
 * it without sorting it again.
 */
public final class AgentCatalog {

    /**
     * Loads both levels and merges them into one catalog. A level that is
     * missing or unreadable-as-missing simply contributes nothing; a real I/O
     * failure is reported, not hidden.
     *
     * @param thePersonalDir user-wide agents directory, {@code null} or absent
     *            when the user has none
     * @param theProjectDir project-level {@code .colleague/agents} directory,
     *            {@code null} or absent when the project has none
     * @return merged profiles sorted by name, never {@code null}
     * @throws IOException on file system failures below either level
     */
    public static List<AgentProfile> load(final File thePersonalDir,
        final File theProjectDir) throws IOException {
        final Map<String, AgentProfile> merged = new LinkedHashMap<>();
        final AgentProfileLoader loader = new AgentProfileLoader();
        for(final AgentProfile profile : loader.loadAll(thePersonalDir)) {
            merged.put(profile.getName(), profile);
        }
        for(final AgentProfile profile : loader.loadAll(theProjectDir)) {
            merged.put(profile.getName(), profile);
        }
        final List<AgentProfile> result = new ArrayList<>(merged.values());
        result.sort((left, right) -> left.getName().compareTo(right.getName()));
        return result;
    }

    private AgentCatalog() {
    }
}
