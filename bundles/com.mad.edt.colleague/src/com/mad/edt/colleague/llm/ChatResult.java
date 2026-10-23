package com.mad.edt.colleague.llm;

/**
 * Result of a chat completion call.
 */
public final class ChatResult {

    private final String content;
    private final String model;
    private final String finishReason;
    private final int promptTokens;
    private final int completionTokens;

    /**
     * @param theContent generated text
     * @param theModel model that produced the text, may be {@code null}
     * @param theFinishReason why generation stopped, may be {@code null}
     * @param thePromptTokens tokens consumed by the prompt, {@code 0} if unknown
     * @param theCompletionTokens tokens generated, {@code 0} if unknown
     */
    public ChatResult(final String theContent, final String theModel, final String theFinishReason,
        final int thePromptTokens, final int theCompletionTokens) {
        content = theContent == null ? "" : theContent;
        model = theModel;
        finishReason = theFinishReason;
        promptTokens = thePromptTokens;
        completionTokens = theCompletionTokens;
    }

    /**
     * @return generated text, never {@code null}
     */
    public String getContent() {
        return content;
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

    @Override
    public String toString() {
        return "ChatResult[" + model + ", " + completionTokens + " tokens, " + finishReason + "]";
    }
}
