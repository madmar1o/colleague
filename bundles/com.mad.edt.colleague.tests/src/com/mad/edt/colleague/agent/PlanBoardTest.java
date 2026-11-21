package com.mad.edt.colleague.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.agent.PlanBoard.Status;
import com.mad.edt.colleague.agent.PlanBoard.Step;

/**
 * Pins the plan board the agent keeps visible to itself: numbered rendering
 * with a status mark per step, at most one step active at a time - a later
 * active step demotes the earlier one - item count capped with the dropped
 * tail announced, over-long step text clipped, and progress counters.
 */
class PlanBoardTest {

    // java:S6126 kept suppressed: the expected value has no trailing newline,
    // a text block would append one and change the byte-exact assertion.
    @SuppressWarnings("java:S6126")
    @Test
    void rendersNumberedStepsWithStatusMarks() {
        final PlanBoard board = new PlanBoard();
        board.replace(List.of(
            new Step("read the module", Status.DONE),
            new Step("fix the query", Status.ACTIVE),
            new Step("run the build", Status.PENDING)));

        final String rendered = board.render();
        assertEquals("Current plan (1/3 done, 1 active):"
            + "\n1. [x] read the module"
            + "\n2. [~] fix the query"
            + "\n3. [ ] run the build", rendered);
    }

    // java:S6126 kept suppressed: the expected value has no trailing newline,
    // a text block would append one and change the byte-exact assertion.
    @SuppressWarnings("java:S6126")
    @Test
    void keepsOnlyTheFirstActiveStep() {
        final PlanBoard board = new PlanBoard();
        board.replace(List.of(
            new Step("first", Status.ACTIVE),
            new Step("second", Status.ACTIVE)));

        assertEquals("Current plan (0/2 done, 1 active):"
            + "\n1. [~] first"
            + "\n2. [ ] second", board.render());
    }

    @Test
    void capsStepsAndAnnouncesTheDroppedTail() {
        final PlanBoard board = new PlanBoard();
        final Step[] many = new Step[PlanBoard.MAX_STEPS + 5];
        for(int index = 0; index < many.length; index++) {
            many[index] = new Step("step " + index, Status.PENDING);
        }
        board.replace(List.of(many));

        final String rendered = board.render();
        final String[] lines = rendered.split("\n");
        // one header line, the capped numbered steps, and the drop notice
        assertEquals(PlanBoard.MAX_STEPS + 2, lines.length);
        assertEquals("+5 more steps did not fit the board and were dropped",
            lines[lines.length - 1]);
    }

    @Test
    void clipsOverLongStepText() {
        final PlanBoard board = new PlanBoard();
        board.replace(List.of(new Step("x".repeat(PlanBoard.MAX_STEP_CHARS + 40),
            Status.PENDING)));

        final String rendered = board.render();
        final String line = rendered.split("\n")[1];
        // "1. [ ] " prefix plus the clip plus the clip marker
        assertEquals("1. [ ] " + "x".repeat(PlanBoard.MAX_STEP_CHARS) + "...", line);
    }

    @Test
    void emptyBoardRendersNoPlan() {
        assertEquals("The plan is empty.", new PlanBoard().render());
    }

    @Test
    void replaceKeepsNothingFromThePreviousPlan() {
        final PlanBoard board = new PlanBoard();
        board.replace(List.of(new Step("old", Status.DONE)));
        board.replace(List.of(new Step("new", Status.PENDING)));

        assertEquals("Current plan (0/1 done, 0 active):\n1. [ ] new", board.render());
    }
}
