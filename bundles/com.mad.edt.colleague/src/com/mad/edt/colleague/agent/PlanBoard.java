package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The step plan an agent run keeps visible: the model replaces the whole
 * board in one call, the board renders it back with a status mark per step.
 * A plan the model can re-read each round keeps long tasks from wandering
 * off course, and the same rendering serves the user as progress. The board
 * enforces the shape itself: one step active at a time, a cap on steps with
 * the dropped tail announced instead of hidden, clipped step text.
 */
public final class PlanBoard {

    /** How many steps the board keeps at most. */
    public static final int MAX_STEPS = 12;

    /** How long one step description may get before it is clipped. */
    public static final int MAX_STEP_CHARS = 160;

    /** Position of one step on the board. */
    public enum Status {
        /** Not started yet. */
        PENDING,
        /** The step being worked on now. */
        ACTIVE,
        /** Finished. */
        DONE
    }

    /** One immutable plan step as accepted from the model. */
    public static final class Step {

        private final String content;
        private final Status status;

        /**
         * @param theContent step description
         * @param theStatus board position
         */
        public Step(final String theContent, final Status theStatus) {
            content = theContent;
            status = theStatus;
        }

        /**
         * @return step description
         */
        public String getContent() {
            return content;
        }

        /**
         * @return board position
         */
        public Status getStatus() {
            return status;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private int droppedCount;

    /**
     * Puts a whole new plan onto the board, keeping nothing of the old one.
     *
     * @param theSteps steps in the order the model listed them
     */
    public synchronized void replace(final List<Step> theSteps) {
        steps.clear();
        droppedCount = Math.max(0, theSteps.size() - MAX_STEPS);
        boolean activeTaken = false;
        for(final Step step : theSteps) {
            if(steps.size() >= MAX_STEPS) {
                break;
            }
            Step accepted = step;
            if(accepted.getContent() != null && accepted.getContent().length() > MAX_STEP_CHARS) {
                accepted = new Step(accepted.getContent().substring(0, MAX_STEP_CHARS) + "...",
                    accepted.getStatus());
            }
            if(accepted.getStatus() == Status.ACTIVE) {
                if(activeTaken) {
                    // one step at a time: the first claim wins, a later
                    // competitor goes back to the queue instead of both
                    // pretending to be in progress
                    accepted = new Step(accepted.getContent(), Status.PENDING);
                } else {
                    activeTaken = true;
                }
            }
            steps.add(accepted);
        }
    }

    /**
     * @return the board as the model and the user see it: a header with
     *         progress counters, one numbered line per step, and a note when
     *         steps did not fit
     */
    public synchronized String render() {
        if(steps.isEmpty()) {
            return "The plan is empty.";
        }
        int done = 0;
        int active = 0;
        for(final Step step : steps) {
            if(step.getStatus() == Status.DONE) {
                done++;
            } else if(step.getStatus() == Status.ACTIVE) {
                active++;
            }
        }
        final StringBuilder rendered = new StringBuilder()
            .append("Current plan (").append(done).append('/').append(steps.size())
            .append(" done, ").append(active).append(" active):");
        int number = 0;
        for(final Step step : steps) {
            number++;
            rendered.append('\n').append(number).append(". ")
                .append(mark(step.getStatus())).append(' ').append(step.getContent());
        }
        if(droppedCount > 0) {
            rendered.append('\n').append('+').append(droppedCount)
                .append(" more steps did not fit the board and were dropped");
        }
        return rendered.toString();
    }

    /**
     * @return how many steps the board currently holds
     */
    public synchronized int stepCount() {
        return steps.size();
    }

    /**
     * @return snapshot of the board's steps in order
     */
    public synchronized List<Step> steps() {
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    private static String mark(final Status theStatus) {
        switch(theStatus) {
            case DONE:
                return "[x]";
            case ACTIVE:
                return "[~]";
            default:
                return "[ ]";
        }
    }
}
