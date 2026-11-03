package com.mad.edt.colleague.agents;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The editor actions must run their questions on a profile that physically
 * cannot write: the picker walks the merged agent catalog and accepts only
 * profiles that name their tools, carry no write tool and may run as the
 * chat itself. Everything else - helper profiles, write profiles and
 * profiles that narrow nothing - is skipped.
 */
class ReadOnlyProfileTest {

    private static AgentProfile profile(final String theName, final AgentProfile.Mode theMode,
        final Set<String> theTools) {
        return new AgentProfile(theName, "d", "s", null, 0, theMode, theTools, false);
    }

    @Test
    @DisplayName("the picker returns the first chat profile whose tool list has no writer")
    void firstReadOnlySkipsWriteAndEmptyProfiles() {
        final AgentProfile writer = profile("writer", AgentProfile.Mode.PRIMARY,
            Set.of("read_file", "write_file"));
        final AgentProfile unrestricted = profile("talker", AgentProfile.Mode.PRIMARY, Set.of());
        final AgentProfile reader = profile("reader", AgentProfile.Mode.PRIMARY,
            Set.of("read_file", "find_metadata"));
        final AgentProfile editor = profile("editor", AgentProfile.Mode.ALL,
            Set.of("edit_file"));

        final AgentProfile picked = ReadOnlyProfile.firstReadOnly(
            List.of(writer, unrestricted, reader, editor));

        Assertions.assertNotNull(picked, "a read-only profile exists and must be found");
        Assertions.assertEquals("reader", picked.getName());
    }

    @Test
    @DisplayName("a subagent profile is never picked even when its tools are read-only")
    void subagentProfilesAreSkipped() {
        final AgentProfile helper = profile("helper", AgentProfile.Mode.SUBAGENT,
            Set.of("read_file"));

        Assertions.assertNull(ReadOnlyProfile.firstReadOnly(List.of(helper)));
    }

    @Test
    @DisplayName("no read-only profile in the catalog, an empty or null catalog give null")
    void missingReadOnlyProfileGivesNull() {
        Assertions.assertNull(ReadOnlyProfile.firstReadOnly(
            List.of(profile("writer", AgentProfile.Mode.PRIMARY, Set.of("write_file")))));
        Assertions.assertNull(ReadOnlyProfile.firstReadOnly(List.of()));
        Assertions.assertNull(ReadOnlyProfile.firstReadOnly(null));
    }
}
