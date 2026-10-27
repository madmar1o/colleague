package com.mad.edt.colleague.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.agent.PlanBoard.Status;

/**
 * Pins the plan tool the model calls to keep its multi-step approach visible
 * and current: it replaces the whole plan in one call, feeds the model back
 * the board as rendered now, treats a missing status as pending, answers a
 * malformed call with an actionable error instead of throwing, and because
 * it changes shared state it never runs side by side with other calls.
 */
class PlanToolTest {

    @Test
    void advertisesTheToolUnderAStableName() {
        final PlanTool tool = new PlanTool(new PlanBoard());
        assertEquals("update_plan", tool.getName());
        assertEquals("update_plan", tool.getSpec().getName());
        assertFalse(tool.getSpec().getDescription().isEmpty());
        assertFalse(tool.isParallelSafe());
    }

    @Test
    void replacesThePlanAndReturnsTheRenderedBoard() {
        final PlanBoard board = new PlanBoard();
        final PlanTool tool = new PlanTool(board);

        final String result = tool.execute("{\"items\":["
            + "{\"content\":\"inspect the register\",\"status\":\"done\"},"
            + "{\"content\":\"write the fix\",\"status\":\"active\"}]}");

        assertEquals("Current plan (1/2 done, 1 active):"
            + "\n1. [x] inspect the register"
            + "\n2. [~] write the fix", result);
        assertEquals(2, board.stepCount());
        assertEquals(Status.ACTIVE, board.steps().get(1).getStatus());
    }

    @Test
    void treatsAMissingStatusAsPending() {
        final PlanTool tool = new PlanTool(new PlanBoard());
        final String result = tool.execute("{\"items\":[{\"content\":\"later\"}]}");

        assertEquals("Current plan (0/1 done, 0 active):\n1. [ ] later", result);
    }

    @Test
    void rejectsAMissingOrMalformedItemsList() {
        final PlanTool tool = new PlanTool(new PlanBoard());

        assertEquals("ERROR: items must be a non-empty array of"
            + " {content, status} objects", tool.execute("{}"));
        assertEquals("ERROR: items must be a non-empty array of"
            + " {content, status} objects", tool.execute("{\"items\":\"whole plan\"}"));
        assertEquals("ERROR: items must be a non-empty array of"
            + " {content, status} objects", tool.execute("not json at all"));
    }

    @Test
    void rejectsAnEntryWithoutContent() {
        final PlanTool tool = new PlanTool(new PlanBoard());

        assertEquals("ERROR: every step needs a non-empty content",
            tool.execute("{\"items\":[{\"status\":\"active\"}]}"));
    }

    @Test
    void unknownStatusIsReportedWithTheAcceptedNames() {
        final PlanTool tool = new PlanTool(new PlanBoard());

        final String result = tool.execute("{\"items\":[{\"content\":\"a\","
            + "\"status\":\"in_progress\"}]}");
        assertTrue(result.startsWith("ERROR: unknown status 'in_progress'"));
        assertTrue(result.contains("pending"));
        assertTrue(result.contains("active"));
        assertTrue(result.contains("done"));
    }

    @Test
    void aWholePlanIsRejectedWhenAnyEntryIsBad() {
        final PlanBoard board = new PlanBoard();
        final PlanTool tool = new PlanTool(board);
        board.replace(List.of(new PlanBoard.Step("kept plan", PlanBoard.Status.DONE)));

        // half an approach applied to the board would leave the model
        // looking at a plan it never asked for
        tool.execute("{\"items\":[{\"content\":\"fine\",\"status\":\"done\"},"
            + "{\"content\":\"\",\"status\":\"pending\"}]}");

        assertEquals(1, board.stepCount());
        assertEquals("Current plan (1/1 done, 0 active):\n1. [x] kept plan", board.render());
    }
}
