package com.mad.edt.colleague.agents;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.agent.ChatMode;

/**
 * The mode boundary inside the tool policy: the plan mode lets the agent
 * observe the workspace and lay out a plan but never lets it change anything,
 * the execute mode hands over the full set the workspace offers, and the ask
 * mode offers no tool at all. This is the gate the acceptance of the three
 * chat modes is checked by: in plan mode the model physically cannot be
 * offered write_file or edit_file, no matter what the tool supplier handed
 * over or what the profile listed.
 */
class ToolPolicyModeTest {

    private final ToolPolicy policy = new ToolPolicy();

    @Test
    @DisplayName("plan mode keeps readers and the plan board and drops every writer")
    void planModeBlocksWritersOnly() {
        Assertions.assertFalse(policy.allowsIn(ChatMode.PLAN, "write_file"),
            "the plan mode must not hand the pen to the model");
        Assertions.assertFalse(policy.allowsIn(ChatMode.PLAN, "edit_file"));
        Assertions.assertTrue(policy.allowsIn(ChatMode.PLAN, "update_plan"),
            "laying out the plan is exactly what this mode is for");
        Assertions.assertTrue(policy.allowsIn(ChatMode.PLAN, "search_code"));
        Assertions.assertTrue(policy.allowsIn(ChatMode.PLAN, "read_file"));
    }

    @Test
    @DisplayName("execute mode hands over everything the workspace offers")
    void executeModeIsTheOldEditMode() {
        Assertions.assertTrue(policy.allowsIn(ChatMode.EXECUTE, "write_file"));
        Assertions.assertTrue(policy.allowsIn(ChatMode.EXECUTE, "edit_file"));
        Assertions.assertTrue(policy.allowsIn(ChatMode.EXECUTE, "search_code"));
    }

    @Test
    @DisplayName("ask mode offers no tool at all")
    void askModeOffersNothing() {
        Assertions.assertFalse(policy.allowsIn(ChatMode.ASK, "search_code"));
        Assertions.assertFalse(policy.allowsIn(ChatMode.ASK, "update_plan"));
        Assertions.assertFalse(policy.allowsIn(ChatMode.ASK, "write_file"));
    }

    @Test
    @DisplayName("the mode gate composes with the profile gate")
    void modeGateAddsToTheProfileGateWithoutReplacingIt() {
        // a profile that lists write_file still cannot get it through the
        // plan mode: the narrower boundary wins
        final AgentProfile writer = new AgentProfile("writer", "d", "s", null, 0, null,
            java.util.Set.of("write_file", "search_code"), false);
        Assertions.assertTrue(policy.allows(writer, "write_file"),
            "the profile alone would offer the writer");
        Assertions.assertFalse(policy.allowsIn(ChatMode.PLAN, "write_file"),
            "the mode closes it back");
        Assertions.assertTrue(policy.allowsIn(ChatMode.EXECUTE, "write_file")
            && policy.allows(writer, "write_file"));
    }
}
