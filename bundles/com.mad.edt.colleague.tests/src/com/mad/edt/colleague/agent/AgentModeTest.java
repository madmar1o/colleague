package com.mad.edt.colleague.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The three chat modes that replace the old edit-mode toggle: ASK talks
 * without ever touching the workspace, PLAN may observe and lay out a plan
 * but not change anything, EXECUTE carries the full write-capable tool set
 * of the old "edit: on". Panels come and go while the workbench lives, so
 * the mode the user picked in one chat session must be what the next panel
 * opens with; the legacy boolean callers must keep working through the
 * compatibility surface.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentModeTest {

    @Test
    @Order(1)
    @DisplayName("a fresh workbench starts in the plain question mode")
    void freshWorkbenchStartsInAsk() {
        Assertions.assertEquals(ChatMode.ASK, AgentMode.current());
    }

    @Test
    @Order(2)
    @DisplayName("the mode picked once is what the next panel session reads")
    void pickedModeSurvivesBetweenPanelSessions() {
        AgentMode.set(ChatMode.PLAN);
        Assertions.assertEquals(ChatMode.PLAN, AgentMode.current());
        AgentMode.set(ChatMode.EXECUTE);
        Assertions.assertEquals(ChatMode.EXECUTE, AgentMode.current());
        AgentMode.set(ChatMode.ASK);
    }

    @Test
    @Order(3)
    @DisplayName("the legacy toggle walks Ask to Execute and back, skipping Plan")
    void legacyToggleCyclesAskExecuteAsk() {
        AgentMode.setOn(false);
        Assertions.assertTrue(AgentMode.toggle(),
            "the old switch jumped straight into the write-capable mode");
        Assertions.assertEquals(ChatMode.EXECUTE, AgentMode.current());
        Assertions.assertFalse(AgentMode.toggle());
        Assertions.assertEquals(ChatMode.ASK, AgentMode.current());
    }

    @Test
    @Order(4)
    @DisplayName("the legacy isOn reads true for any agent mode, plan included")
    void legacyIsOnCoversBothAgentModes() {
        AgentMode.set(ChatMode.PLAN);
        Assertions.assertTrue(AgentMode.isOn(),
            "plan mode runs tools too, the old gate must not close it out");
        AgentMode.set(ChatMode.EXECUTE);
        Assertions.assertTrue(AgentMode.isOn());
        AgentMode.set(ChatMode.ASK);
        Assertions.assertFalse(AgentMode.isOn());
    }

    @Test
    @Order(5)
    @DisplayName("the legacy setOn lands on the two ends of the cycle")
    void legacySetOnMapsToTheEnds() {
        AgentMode.setOn(true);
        Assertions.assertEquals(ChatMode.EXECUTE, AgentMode.current());
        AgentMode.setOn(false);
        Assertions.assertEquals(ChatMode.ASK, AgentMode.current());
    }

    @Test
    @Order(6)
    @DisplayName("page wire names parse back into modes, unknown ones fall to ASK")
    void pageWireNamesParseIntoModes() {
        Assertions.assertEquals(ChatMode.ASK, ChatMode.of("ask"));
        Assertions.assertEquals(ChatMode.PLAN, ChatMode.of("plan"));
        Assertions.assertEquals(ChatMode.EXECUTE, ChatMode.of("execute"));
        Assertions.assertEquals(ChatMode.PLAN, ChatMode.of(" PLAN "),
            "the page may send padding or a different case, the wire must be forgiving");
        Assertions.assertEquals(ChatMode.ASK, ChatMode.of("teleport"));
        Assertions.assertEquals(ChatMode.ASK, ChatMode.of(null));
    }

    @Test
    @Order(7)
    @DisplayName("each mode speaks its own page wire name")
    void modesSpeakTheirPageWireNames() {
        Assertions.assertEquals("ask", ChatMode.ASK.wireName());
        Assertions.assertEquals("plan", ChatMode.PLAN.wireName());
        Assertions.assertEquals("execute", ChatMode.EXECUTE.wireName());
    }

    @Test
    @Order(8)
    @DisplayName("the three-state control walks Ask to Plan to Execute and back")
    void theCycleWalksAllThreeModes() {
        Assertions.assertEquals(ChatMode.PLAN, ChatMode.ASK.next());
        Assertions.assertEquals(ChatMode.EXECUTE, ChatMode.PLAN.next());
        Assertions.assertEquals(ChatMode.ASK, ChatMode.EXECUTE.next());
    }
}
