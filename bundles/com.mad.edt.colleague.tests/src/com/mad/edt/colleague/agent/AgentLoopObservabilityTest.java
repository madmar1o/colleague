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
 * Step trace of a run. Unattended end-to-end verification can only assert on
 * what the loop actually did - which round-trips happened, which tools ran,
 * with what arguments and how long they took. Without an observer these
 * facts are invisible from the outside, so the loop must report them.
 */
class AgentLoopObservabilityTest {

    /**
     * Round-trips the scripted list on each call.
     */
    private static class ScriptedProvider implements ILLMProvider {

        private final List<ChatResult> script;
        private int next;

        ScriptedProvider(final List<ChatResult> theScript) {
            script = theScript;
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
            return script.get(next++);
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
            final Consumer<String> theOnThinking) {
            return script.get(next++);
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

    /**
     * Tool with a known name and a predictable result.
     */
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

    /**
     * Collects events as plain strings so assertions read like the trace.
     */
    private static final class RecordingObserver implements AgentObserver {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onRound(final int theStep, final int theRequestedToolCalls) {
            events.add("round " + theStep + " tools=" + theRequestedToolCalls);
        }

        @Override
        public void onToolCall(final int theStep, final String theName, final String theArgumentsJson,
            final String theResult, final long theElapsedMs) {
            events.add("tool " + theStep + " " + theName + " " + theArgumentsJson
                + " result=" + theResult + " ms>=" + (theElapsedMs >= 0));
        }
    }

    private static ChatResult toolRound(final String theId, final String theName, final String theArgs) {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall(theId, theName, theArgs)));
    }

    private static ChatResult finalRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    @Test
    @DisplayName("a tool round then a final answer leaves a complete trace")
    void toolRunLeavesTrace() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(List.of(
            toolRound("t1", "read_file", "{\"path\":\"a.txt\"}"),
            finalRound("all done")));
        final RecordingObserver observer = new RecordingObserver();
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 5,
            new CancellationFlag(), observer).run("sys", "task");

        Assertions.assertEquals("all done", answer.getText());
        Assertions.assertEquals(List.of(
            "round 1 tools=1",
            "tool 1 read_file {\"path\":\"a.txt\"} result=file body ms>=true",
            "round 2 tools=0"), observer.events);
    }

    @Test
    @DisplayName("an unknown tool call is reported as an error result")
    void unknownToolIsTraceable() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(List.of(
            toolRound("t1", "no_such", "{}"),
            finalRound("recovered")));
        final RecordingObserver observer = new RecordingObserver();
        new AgentLoop(provider, List.of(new StubTool()), "stub", 5,
            new CancellationFlag(), observer).run("sys", "task");

        Assertions.assertEquals(3, observer.events.size());
        final String toolEvent = observer.events.get(1);
        Assertions.assertTrue(toolEvent.startsWith("tool 1 no_such "), toolEvent);
        Assertions.assertTrue(toolEvent.contains("result=ERROR: unknown tool 'no_such'"), toolEvent);
    }

    @Test
    @DisplayName("the exhausted budget is visible round by round")
    void exhaustedBudgetStillReportsRounds() throws Exception {
        final ChatResult looping = toolRound("t1", "read_file", "{}");
        final ScriptedProvider provider = new ScriptedProvider(List.of(looping, looping, looping));
        final RecordingObserver observer = new RecordingObserver();
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 3,
            new CancellationFlag(), observer).run("sys", "task");

        Assertions.assertFalse(answer.isCompleted());
        Assertions.assertEquals(List.of("round 1 tools=1", "tool 1 read_file {} result=file body ms>=true",
            "round 2 tools=1", "tool 2 read_file {} result=file body ms>=true",
            "round 3 tools=1", "tool 3 read_file {} result=file body ms>=true"), observer.events);
    }
}
