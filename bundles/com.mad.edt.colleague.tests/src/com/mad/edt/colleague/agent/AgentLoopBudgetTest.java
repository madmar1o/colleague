package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Generation-budget contract of the ReAct loop. Reasoning-capable models emit
 * their tool calls only after the reasoning pass, so a small budget cuts the
 * call away in silence: the loop must pin a generous per-round-trip budget,
 * keep the reasoning pass on, and never hand the user an empty final answer.
 */
class AgentLoopBudgetTest {

    /**
     * Scripted endpoint recording every request it receives.
     */
    private static class RecordingProvider implements ILLMProvider {

        private final List<ChatResult> script = new ArrayList<>();
        private final List<ChatRequest> seen = new ArrayList<>();

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public ProviderConfig getConfig() {
            return null;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) {
            seen.add(theRequest);
            final int index = seen.size() - 1;
            return index < script.size() ? script.get(index)
                : new ChatResult("done", null, "stub", "stop", 1, 1, List.of());
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
    @DisplayName("every round-trip pins a budget wide enough for reasoning plus a tool call")
    void budgetIsPinned() throws Exception {
        final RecordingProvider provider = new RecordingProvider();
        new AgentLoop(provider, List.of(), "stub", 3, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals(1, provider.seen.size());
        final Integer budget = provider.seen.get(0).getMaxTokens();
        Assertions.assertNotNull(budget, "the loop must state its own generation budget");
        Assertions.assertTrue(budget.intValue() >= 2_048,
            "tool-calling reasoning models need room for the reasoning pass and the call: " + budget);
    }

    @Test
    @DisplayName("the loop asks for the reasoning pass that carries its tool calls")
    void reasoningStaysOn() throws Exception {
        final RecordingProvider provider = new RecordingProvider();
        new AgentLoop(provider, List.of(), "stub", 2, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals(Boolean.TRUE, provider.seen.get(0).getThink(),
            "tool-call syntax only reliably appears in the reasoning mode of local models");
    }

    @Test
    @DisplayName("a silent final round-trip yields a visible notice instead of an empty answer")
    void silentAnswerBecomesNotice() throws Exception {
        final RecordingProvider provider = new RecordingProvider();
        provider.script.add(new ChatResult("   ", "long reasoning cut away", "stub", "length",
            100, 2_048, List.of()));
        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "stub", 3, new CancellationFlag()).run("sys", "task");
        Assertions.assertFalse(answer.getText().trim().isEmpty(),
            "the user must never be handed an empty bubble after a silent model turn");
    }
}
