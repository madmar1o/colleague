package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable request for a chat completion: model name, conversation history
 * and optional generation parameters.
 */
public final class ChatRequest {

    private final String model;
    private final List<ChatMessage> messages;
    private final List<ToolSpec> tools;
    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean think;
    private final CancellationFlag cancellation;

    private ChatRequest(final Builder theBuilder) {
        if(theBuilder.model == null || theBuilder.model.isEmpty()) {
            throw new IllegalArgumentException("model name is empty");
        }
        if(theBuilder.messages.isEmpty()) {
            throw new IllegalArgumentException("no messages in request");
        }
        model = theBuilder.model;
        messages = Collections.unmodifiableList(new ArrayList<>(theBuilder.messages));
        tools = Collections.unmodifiableList(new ArrayList<>(theBuilder.tools));
        temperature = theBuilder.temperature;
        maxTokens = theBuilder.maxTokens;
        think = theBuilder.think;
        cancellation = theBuilder.cancellation == null
            ? new CancellationFlag() : theBuilder.cancellation;
    }

    /**
     * @return the model identifier as understood by the provider
     */
    public String getModel() {
        return model;
    }

    /**
     * @return conversation history, oldest message first
     */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /**
     * @return callable tools advertised to the model, empty when none
     */
    public List<ToolSpec> getTools() {
        return tools;
    }

    /**
     * @return sampling temperature in {@code [0, 1]}, or {@code null} for the provider default
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * @return maximum number of generated tokens, or {@code null} for the provider default
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * @return whether the model may produce an intermediate reasoning pass, or
     *         {@code null} to leave it to the provider default
     */
    public Boolean getThink() {
        return think;
    }

    /**
     * @return the cancellation latch of this request; providers poll it and
     *         stop producing as soon as the owner of the turn gives up
     */
    public CancellationFlag getCancellation() {
        return cancellation;
    }

    /**
     * @param theModel model identifier
     * @return a new builder seeded with the model
     */
    public static Builder builder(final String theModel) {
        return new Builder(theModel);
    }

    /**
     * Builder for {@link ChatRequest}.
     */
    public static final class Builder {

        private final String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private Boolean think;
        private CancellationFlag cancellation;

        private Builder(final String theModel) {
            model = theModel;
        }

        /**
         * Appends one message to the conversation.
         *
         * @param theMessage the message
         * @return this builder
         */
        public Builder addMessage(final ChatMessage theMessage) {
            messages.add(theMessage);
            return this;
        }

        /**
         * Advertises callable tools to the model.
         *
         * @param theTools the tools
         * @return this builder
         */
        public Builder tools(final List<ToolSpec> theTools) {
            tools.addAll(theTools);
            return this;
        }

        /**
         * Sets sampling temperature.
         *
         * @param theTemperature value in {@code [0, 1]}
         * @return this builder
         */
        public Builder temperature(final double theTemperature) {
            temperature = Double.valueOf(theTemperature);
            return this;
        }

        /**
         * Sets the generation budget.
         *
         * @param theMaxTokens maximum generated tokens
         * @return this builder
         */
        public Builder maxTokens(final int theMaxTokens) {
            maxTokens = Integer.valueOf(theMaxTokens);
            return this;
        }

        /**
         * Enables or suppresses the model's intermediate reasoning pass. Only
         * reasoning-capable local runtimes interpret it.
         *
         * @param theThink {@code false} to answer directly
         * @return this builder
         */
        public Builder think(final boolean theThink) {
            think = Boolean.valueOf(theThink);
            return this;
        }

        /**
         * Attaches the cancellation latch of the turn this request belongs to.
         *
         * @param theCancellation shared flag, {@code null} for a private
         *            never-cancelled one
         * @return this builder
         */
        public Builder cancellation(final CancellationFlag theCancellation) {
            cancellation = theCancellation;
            return this;
        }

        /**
         * @return the immutable request
         */
        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
