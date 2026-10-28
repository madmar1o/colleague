package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ToolCall;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * The shape of the repair round after a tool round went silent: when a
 * round-trip that follows executed tools ends with thinking but neither text
 * nor a call, the repair must not repeat the very mistake it is fixing -
 * thinking suppressed AND a wider generation budget, otherwise the same
 * token ceiling eats the answer again and the repair is doomed in advance.
 */
class AgentLoopEmptyFinalTest {

    /** serves the scripted rounds and records every request it received */
    private static final class ScriptedProvider implements ILLMProvider {

        private final List<ChatResult> script;
        final List<ChatRequest> seen = new ArrayList<>();
        private int next;

        ScriptedProvider(final ChatResult... theScript) {
            script = List.of(theScript);
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
        public ChatResult chat(final ChatRequest theRequest) {
            seen.add(theRequest);
            return script.get(Math.min(next++, script.size() - 1));
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            seen.add(theRequest);
            return script.get(Math.min(next++, script.size() - 1));
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

    /** a tool the scripted tool round can execute */
    private static final class StubTool implements IToolExecutor {

        @Override
        public String getName() {
            return "read_file";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("read_file", "reads a file", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            return "file body";
        }
    }

    private static ChatResult toolRound() {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall("t1", "read_file", "{}")));
    }

    /** thinking ran, generation died: no text, no call */
    private static ChatResult emptyThinkingRound() {
        return new ChatResult("", "pondering without end", "stub", "stop", 1, 1, List.of());
    }

    private static ChatResult textRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    @Test
    @DisplayName("the repair round thinks not and budgets at least double")
    void repairRoundIsCheapOnThinkingWideOnBudget() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(toolRound(), emptyThinkingRound(),
            textRound("final after repair"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 8,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals("final after repair", answer.getText());
        Assertions.assertEquals(3, provider.seen.size(),
            "tool round, empty round, repair round");

        final ChatRequest empty = provider.seen.get(1);
        Assertions.assertEquals(Boolean.TRUE, empty.getThink(),
            "the ordinary round keeps the thinking pass on");
        final ChatRequest repair = provider.seen.get(2);
        Assertions.assertEquals(Boolean.FALSE, repair.getThink(),
            "the repair must drop the thinking pass that just ate the budget");
        Assertions.assertNotNull(repair.getMaxTokens(),
            "the repair must widen the generation budget, not repeat the same ceiling");
        Assertions.assertTrue(repair.getMaxTokens().intValue()
                >= 2 * empty.getMaxTokens().intValue(),
            "the repair budget must be at least double the round-trip one, got "
                + repair.getMaxTokens() + " vs " + empty.getMaxTokens());
    }

    @Test
    @DisplayName("the repair round shows up in the round counter the observer sees")
    void repairRoundIsVisibleToTheObserver() throws Exception {
        final List<int[]> rounds = new ArrayList<>();
        final ScriptedProvider provider = new ScriptedProvider(toolRound(), emptyThinkingRound(),
            textRound("final after repair"));
        final AgentLoop loop = new AgentLoop(provider, List.of(new StubTool()), "stub", 8,
            new CancellationFlag(), new AgentObserver() {
                @Override
                public void onRound(final int theStep, final int theRequestedToolCalls) {
                    rounds.add(new int[] {theStep, theRequestedToolCalls});
                }
            });
        loop.run("sys", "task");

        Assertions.assertTrue(rounds.size() >= 2,
            "the tool round and the final round must both be reported");
        final int lastStep = rounds.get(rounds.size() - 1)[0];
        Assertions.assertTrue(lastStep >= 3,
            "the repair round must be counted in the visible round number, got " + lastStep);
    }
}
