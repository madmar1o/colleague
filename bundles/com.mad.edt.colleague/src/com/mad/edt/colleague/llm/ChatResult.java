package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a chat completion call.
 */
public final class ChatResult {

    private final String content;
    private final String thinking;
    private final String model;
    private final String finishReason;
    private final int promptTokens;
    private final int completionTokens;
    private final List<ToolCall> toolCalls;

    /**
     * @param theContent generated text
     * @param theModel model that produced the text, may be {@code null}
     * @param theFinishReason why generation stopped, may be {@code null}
     * @param thePromptTokens tokens consumed by the prompt, {@code 0} if unknown
     * @param theCompletionTokens tokens generated, {@code 0} if unknown
     */
    public ChatResult(final String theContent, final String theModel, final String theFinishReason,
        final int thePromptTokens, final int theCompletionTokens) {
        this(theContent, theModel, theFinishReason, thePromptTokens, theCompletionTokens, null);
    }

    /**
     * @param theContent generated text
     * @param theModel model that produced the text, may be {@code null}
     * @param theFinishReason why generation stopped, may be {@code null}
     * @param thePromptTokens tokens consumed by the prompt, {@code 0} if unknown
     * @param theCompletionTokens tokens generated, {@code 0} if unknown
     * @param theToolCalls tool calls requested by the model, may be {@code null}
     */
    public ChatResult(final String theContent, final String theModel, final String theFinishReason,
        final int thePromptTokens, final int theCompletionTokens, final List<ToolCall> theToolCalls) {
        this(theContent, null, theModel, theFinishReason, thePromptTokens, theCompletionTokens,
            theToolCalls);
    }

    /**
     * @param theContent generated answer text
     * @param theThinking separate reasoning pass of the model, may be {@code null}
     * @param theModel model that produced the text, may be {@code null}
     * @param theFinishReason why generation stopped, may be {@code null}
     * @param thePromptTokens tokens consumed by the prompt, {@code 0} if unknown
     * @param theCompletionTokens tokens generated, {@code 0} if unknown
     * @param theToolCalls tool calls requested by the model, may be {@code null}
     */
    public ChatResult(final String theContent, final String theThinking, final String theModel,
        final String theFinishReason, final int thePromptTokens, final int theCompletionTokens,
        final List<ToolCall> theToolCalls) {
        content = theContent == null ? "" : theContent;
        thinking = theThinking;
        model = theModel;
        finishReason = theFinishReason;
        promptTokens = thePromptTokens;
        completionTokens = theCompletionTokens;
        toolCalls = theToolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(theToolCalls));
    }

    /**
     * @return generated text, never {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * @return the model's intermediate reasoning pass, {@code null} when the
     *         model produced none or the transport does not separate it
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * @return model reported by the provider, may be {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * @return stop reason reported by the provider, may be {@code null}
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * @return tokens consumed by the prompt, {@code 0} when not reported
     */
    public int getPromptTokens() {
        return promptTokens;
    }

    /**
     * @return generated tokens, {@code 0} when not reported
     */
    public int getCompletionTokens() {
        return completionTokens;
    }

    /**
     * @return tool calls requested by the model, empty when the answer is final
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * @return {@code true} when the model asked to execute tools
     */
    public boolean wantsToolCall() {
        return !toolCalls.isEmpty();
    }

    /**
     * @return {@code true} when the runtime stopped the answer because the
     *         window ran out: the provider reported the stop reason
     *         {@code length}, which means the oldest prompt text had been
     *         cut away and the answer is only as complete as the surviving
     *         tail of the prompt
     */
    public boolean truncated() {
        return "length".equals(finishReason);
    }

    @Override
    public String toString() {
        return "ChatResult[" + model + ", " + completionTokens + " tokens, " + finishReason
            + (wantsToolCall() ? ", " + toolCalls.size() + " toolCalls" : "") + "]";
    }
}
