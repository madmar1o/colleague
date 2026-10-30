package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mad.edt.colleague.llm.ChatMessage;

/**
 * Final outcome of an {@link AgentLoop} run.
 */
public final class AgentAnswer {

    private final String text;
    private final int steps;
    private final int executedToolCalls;
    private final boolean completed;
    /**
     * The conversation as it stood when the run ended. A completed run needs
     * it for nothing; a budget-exhausted one hands it over so the caller can
     * continue the very same exchange instead of starting the task blind.
     */
    private final List<ChatMessage> conversation;

    /**
     * @param theText final assistant text
     * @param theSteps model round-trips performed
     * @param theExecutedToolCalls tools executed during the run
     * @param theCompleted {@code false} when the step budget was exhausted
     */
    public AgentAnswer(final String theText, final int theSteps, final int theExecutedToolCalls,
        final boolean theCompleted) {
        this(theText, theSteps, theExecutedToolCalls, theCompleted, List.of());
    }

    /**
     * @param theText final assistant text
     * @param theSteps model round-trips performed
     * @param theExecutedToolCalls tools executed during the run
     * @param theCompleted {@code false} when the step budget was exhausted
     * @param theConversation messages of the run at its end, copied here;
     *            the continuation entry of {@link AgentLoop} takes it back
     */
    public AgentAnswer(final String theText, final int theSteps, final int theExecutedToolCalls,
        final boolean theCompleted, final List<ChatMessage> theConversation) {
        text = theText == null ? "" : theText;
        steps = theSteps;
        executedToolCalls = theExecutedToolCalls;
        completed = theCompleted;
        conversation = Collections.unmodifiableList(new ArrayList<>(
            theConversation == null ? List.of() : theConversation));
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

    /**
     * @return messages of the run at its end, in request order, system
     *            message first when one was used; empty for runs that were
     *            created without one
     */
    public List<ChatMessage> getConversation() {
        return conversation;
    }

    @Override
    public String toString() {
        return "AgentAnswer[steps=" + steps + ", tools=" + executedToolCalls
            + ", completed=" + completed + "]";
    }
}
