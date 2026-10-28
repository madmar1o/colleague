package com.mad.edt.colleague.agent.subagent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A finished delegate stays reachable for a while: the same agent name can be
 * called again and continues its conversation. Only the most recent delegates
 * are kept, the oldest one leaves when the shelf is full.
 */
class SubagentRegistryTest {

    @Test
    @DisplayName("a session is kept under its name and can be taken back")
    void sessionIsKeptUnderItsName() {
        final SubagentRegistry registry = new SubagentRegistry();

        registry.put("explorer", new SubagentRegistry.Session("look", "plain"));
        final SubagentRegistry.Session live = registry.get("explorer");

        Assertions.assertNotNull(live);
        Assertions.assertEquals("plain", live.modelName());
        Assertions.assertNull(registry.get("nobody"));
    }

    @Test
    @DisplayName("the shelf holds eight delegates, the oldest one is evicted")
    void shelfIsBoundedAndEvictsTheOldest() {
        final SubagentRegistry registry = new SubagentRegistry();

        for(int index = 0; index < SubagentRegistry.MAX_SESSIONS + 2; index++) {
            registry.put("agent" + index, new SubagentRegistry.Session("task", "plain"));
        }

        Assertions.assertEquals(SubagentRegistry.MAX_SESSIONS, registry.size());
        Assertions.assertNull(registry.get("agent0"), "the oldest delegate left the shelf");
        Assertions.assertNotNull(registry.get("agent1"));
        Assertions.assertNotNull(registry.get("agent9"));
    }

    @Test
    @DisplayName("re-naming an existing delegate replaces it without growing the shelf")
    void sameNameReplacesInPlace() {
        final SubagentRegistry registry = new SubagentRegistry();

        registry.put("explorer", new SubagentRegistry.Session("first", "plain"));
        registry.put("explorer", new SubagentRegistry.Session("second", "plain"));

        Assertions.assertEquals(1, registry.size());
        Assertions.assertEquals("second", registry.get("explorer").description());
    }
}
