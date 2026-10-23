package com.mad.edt.colleague.agent;

/**
 * Final outcome of an {@link AgentLoop} run.
 */
public final class AgentAnswer {

    private final String text;
    private final int steps;
    private final int executedToolCalls;
    private final boolean completed;

    /**
     * @param theText final assistant text
     * @param theSteps model round-trips performed
     * @param theExecutedToolCalls tools executed during the run
     * @param theCompleted {@code false} when the step budget was exhausted
     */
    public AgentAnswer(final String theText, final int theSteps, final int theExecutedToolCalls,
        final boolean theCompleted) {
        text = theText == null ? "" : theText;
        steps = theSteps;
        executedToolCalls = theExecutedToolCalls;
        completed = theCompleted;
    }

    /**
     * @return final answer text
     */
    public String getText() {
        return text;
    }

    /**
     * @return number of model round-trips
     */
    public int getSteps() {
        return steps;
    }

    /**
     * @return number of executed tool calls
     */
    public int getExecutedToolCalls() {
        return executedToolCalls;
    }

    /**
     * @return {@code true} when the model produced a final answer within budget
     */
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public String toString() {
        return "AgentAnswer[steps=" + steps + ", tools=" + executedToolCalls
            + ", completed=" + completed + "]";
    }
}
