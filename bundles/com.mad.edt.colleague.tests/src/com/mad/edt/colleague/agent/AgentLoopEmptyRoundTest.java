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
 * Recovery from an empty final round. A local reasoning model that spends
 * its whole generation budget thinking ends the round with neither answer
 * text nor tool call; the loop must not hand that turn to the user as the
 * final word but make one cheaper repair attempt - the same task without
 * the thinking pass - while the step budget still bounds the run.
 */
class AgentLoopEmptyRoundTest {

    /**
     * Serves the scripted rounds and records every request it received.
     */
    private static class ScriptedProvider implements ILLMProvider {

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
        public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
            final Consumer<String> theOnThinking) {
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

    private static class StubTool implements IToolExecutor {

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

    private static ChatResult emptyRound() {
        return new ChatResult("", null, "stub", "stop", 1, 1, List.of());
    }

    private static ChatResult textRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    private static ChatResult toolRound() {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall("t1", "read_file", "{}")));
    }

    @Test
    @DisplayName("an empty final round gets one repair without the thinking pass")
    void emptyRoundIsRepaired() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(emptyRound(), textRound("repaired answer"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals("repaired answer", answer.getText(),
            "an empty round is a cut-off thinking pass, not a final answer");
        Assertions.assertEquals(2, answer.getSteps());
        Assertions.assertEquals(Boolean.TRUE, provider.seen.get(0).getThink());
        Assertions.assertEquals(Boolean.FALSE, provider.seen.get(1).getThink(),
            "the repair drops the reasoning pass that just ate the whole budget");
    }

    @Test
    @DisplayName("the repair round may still call tools")
    void repairRoundMayCallTools() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(emptyRound(), toolRound(),
            textRound("done after tool"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals("done after tool", answer.getText());
        Assertions.assertEquals(1, answer.getExecutedToolCalls());
        Assertions.assertEquals(3, answer.getSteps());
    }

    @Test
    @DisplayName("a second empty round ends the run with the visible notice")
    void secondEmptyRoundEndsWithNotice() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(emptyRound(), emptyRound(),
            textRound("must not be reached"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertTrue(answer.getText().contains("ended the round without an answer"),
            answer.getText());
        Assertions.assertEquals(2, provider.seen.size(),
            "one repair per round-trip: the loop never chases an unfixable model");
    }

    @Test
    @DisplayName("no repair is attempted when the step budget is used up")
    void noRepairWithoutBudget() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(emptyRound(), textRound("too late"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(), "stub", 1,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertTrue(answer.getText().contains("ended the round without an answer"),
            answer.getText());
        Assertions.assertEquals(1, provider.seen.size());
    }
}
