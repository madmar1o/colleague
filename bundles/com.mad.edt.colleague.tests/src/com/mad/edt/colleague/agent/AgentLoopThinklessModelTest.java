package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Pins the thinking policy of the agent loop to the provider's own knowledge
 * of the model. The loop used to request the reasoning pass for every model,
 * so a model without that capability answered the request with a hard
 * rejection and the turn never started. The capability question belongs to
 * the provider: it is the only side that learns from a rejection.
 */
class AgentLoopThinklessModelTest {

    /** Answers with plain text and records what the loop actually asked for. */
    private static final class CapabilityProvider implements ILLMProvider {

        private final boolean ourThinking;
        final List<ChatRequest> seen = new ArrayList<>();

        CapabilityProvider(final boolean theThinking) {
            ourThinking = theThinking;
        }

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public ProviderConfig getConfig() {
            return null;
        }

        @Override
        public boolean supportsThinking(final String theModel) {
            return ourThinking;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) {
            seen.add(theRequest);
            return new ChatResult("done", null, "stub", "stop", 1, 1, List.of());
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            seen.add(theRequest);
            return new ChatResult("done", null, "stub", "stop", 1, 1, List.of());
        }

        @Override
        public List<String> listModels() {
            return List.of("stub");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    @Test
    @DisplayName("a model without thinking support is never asked for the reasoning pass")
    void skipsTheThinkingPassForAThinklessModel() throws Exception {
        final CapabilityProvider provider = new CapabilityProvider(false);

        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "thinkless", 3).run("system", "task");

        Assertions.assertEquals("done", answer.getText());
        Assertions.assertEquals(1, provider.seen.size());
        Assertions.assertNotEquals(Boolean.TRUE, provider.seen.get(0).getThink(),
            "the loop must not request a pass the model rejects");
    }

    @Test
    @DisplayName("a reasoning model still gets the thinking pass")
    void keepsTheThinkingPassForAReasoningModel() throws Exception {
        final CapabilityProvider provider = new CapabilityProvider(true);

        new AgentLoop(provider, List.of(), "reasoning", 3).run("system", "task");

        Assertions.assertEquals(Boolean.TRUE, provider.seen.get(0).getThink(),
            "suppressing the pass would cost the tool-call syntax of reasoning models");
    }
}
