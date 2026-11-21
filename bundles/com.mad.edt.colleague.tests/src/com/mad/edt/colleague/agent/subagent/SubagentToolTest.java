package com.mad.edt.colleague.agent.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ToolCall;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * A delegate runs the same loop the chat runs, but one level down: its own
 * step budget, only the read-only tools of its parent, and its answer comes
 * back as the result of one tool call. The nesting stays bounded - a delegate
 * may not delegate again - and several delegates in one round reach the model
 * in the order the model asked for them.
 */
class SubagentToolTest {

    // fixture vocabulary: the tool names the parent offers and the answers the
    // nested loop is scripted to hand back
    private static final String READ_FILE = "read_file";
    private static final String WRITE_FILE = "write_file";
    private static final String DELEGATE_ANSWER = "delegate found 12 modules";
    private static final String FIRST_ANSWER = "first delegate answer";

    /** serves the scripted rounds and records every request it received */
    private static final class ScriptedProvider implements ILLMProvider {

        private final List<ChatResult> script;
        final List<ChatRequest> requests = new ArrayList<>();
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
            requests.add(theRequest);
            return script.get(Math.min(next++, script.size() - 1));
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            requests.add(theRequest);
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

    /** reader that counts how often the nested loop called it */
    private static final class ReaderTool implements IToolExecutor {

        int calls;

        @Override
        public String getName() {
            return READ_FILE;
        }

        @Override
        public boolean isParallelSafe() {
            return true;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(READ_FILE, "reads a file", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            calls++;
            return "file body";
        }
    }

    /** writer that must never reach a delegate */
    private static final class WriterTool implements IToolExecutor {

        @Override
        public String getName() {
            return WRITE_FILE;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(WRITE_FILE, "writes a file", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            return "written";
        }
    }

    private static ChatResult toolRound(final String... theCalls) {
        final List<ToolCall> calls = new ArrayList<>();
        for(final String call : theCalls) {
            final String[] parts = call.split("\\|");
            calls.add(new ToolCall(parts[0], parts[1], parts[2]));
        }
        return new ChatResult("", null, "stub", "tool_calls", 1, 1, calls);
    }

    private static ChatResult textRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    private static List<String> advertised(final ChatRequest theRequest) {
        return theRequest.getTools().stream().map(ToolSpec::getName).toList();
    }

    @Test
    @DisplayName("a foreground delegate runs its own loop and returns its final answer")
    void foregroundSubagentRunsNestedLoop() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(
            toolRound("d1|subagent|{\"agent\":\"explorer\",\"description\":\"count files\","
                + "\"prompt\":\"count the modules\"}"),
            toolRound("r1|read_file|{\"path\":\"a.java\"}"),
            textRound(DELEGATE_ANSWER),
            textRound("parent says: delegate found 12 modules"));
        final ReaderTool reader = new ReaderTool();
        final SubagentTool tool = new SubagentTool(provider, "stub",
            List.of(reader, new WriterTool()), List.of(), 0, new CancellationFlag());

        final AgentAnswer answer = new AgentLoop(provider, List.of(tool), "stub", 8,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertTrue(answer.getText().contains(DELEGATE_ANSWER),
            answer.getText());
        Assertions.assertEquals(1, reader.calls,
            "the delegate ran its own tool round");
        // request 0: the parent round. request 1: the delegate first round -
        // it must see the reader but never the writer
        final List<String> delegateTools = advertised(provider.requests.get(1));
        Assertions.assertTrue(delegateTools.contains(READ_FILE), delegateTools.toString());
        Assertions.assertFalse(delegateTools.contains(WRITE_FILE),
            "a delegate must not be offered a writer: " + delegateTools);
        // request 2: the delegate second round; request 3: the parent final
        final ChatRequest parentFinal = provider.requests.get(3);
        final List<String> toolResults = parentFinal.getMessages().stream()
            .filter(message -> ChatMessage.ROLE_TOOL.equals(message.getRole()))
            .map(ChatMessage::getContent).toList();
        Assertions.assertEquals(1, toolResults.size());
        Assertions.assertTrue(toolResults.get(0).contains(DELEGATE_ANSWER),
            toolResults.get(0));
    }

    @Test
    @DisplayName("a delegate may not delegate again")
    void depthLimitRefusesNesting() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider();
        final SubagentTool nested = new SubagentTool(provider, "stub",
            List.of(new ReaderTool()), List.of(), 1, new CancellationFlag());

        final String result = nested.execute(
            "{\"agent\":\"deeper\",\"description\":\"one more level\",\"prompt\":\"go\"}");

        Assertions.assertTrue(result.startsWith("ERROR"), result);
        Assertions.assertTrue(result.toLowerCase().contains("depth"),
            "the failure must name the reason: " + result);
        Assertions.assertTrue(provider.requests.isEmpty(),
            "a refused delegate must not reach the model at all");
    }

    @Test
    @DisplayName("the same delegate name picks its conversation back up")
    void liveSessionContinuesTheSameDelegate() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(
            textRound(FIRST_ANSWER),
            textRound("second delegate answer"));
        final SubagentTool tool = new SubagentTool(provider, "stub",
            List.of(new ReaderTool()), List.of(), 0, new CancellationFlag());

        Assertions.assertEquals(FIRST_ANSWER,
            tool.execute("{\"agent\":\"explorer\",\"description\":\"look\",\"prompt\":\"task one\"}"));
        Assertions.assertEquals("second delegate answer",
            tool.execute("{\"agent\":\"explorer\",\"description\":\"look again\",\"prompt\":\"task two\"}"));

        final List<String> secondTask =
            provider.requests.get(1).getMessages().stream().map(ChatMessage::getContent).toList();
        Assertions.assertTrue(secondTask.stream().anyMatch(text -> text.contains(FIRST_ANSWER)),
            "the delegate continues where it stopped: " + secondTask);
    }

    @Test
    @DisplayName("two delegates in one round come back in the order they were called")
    void resultsKeepCallOrder() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(
            toolRound("c1|subagent|{\"agent\":\"first\",\"description\":\"first\",\"prompt\":\"ask one\"}",
                "c2|subagent|{\"agent\":\"second\",\"description\":\"second\",\"prompt\":\"ask two\"}"),
            textRound("answer one"),
            textRound("answer two"),
            textRound("parent done"));
        final SubagentTool tool = new SubagentTool(provider, "stub",
            List.of(new ReaderTool()), List.of(), 0, new CancellationFlag());

        new AgentLoop(provider, List.of(tool), "stub", 8, new CancellationFlag())
            .run("sys", "task");

        final ChatRequest parentFinal = provider.requests.get(3);
        final List<ChatMessage> results = parentFinal.getMessages().stream()
            .filter(message -> ChatMessage.ROLE_TOOL.equals(message.getRole()))
            .toList();
        Assertions.assertEquals(2, results.size());
        Assertions.assertEquals("c1", results.get(0).getToolCallId());
        Assertions.assertTrue(results.get(0).getContent().contains("answer one"),
            results.get(0).getContent());
        Assertions.assertEquals("c2", results.get(1).getToolCallId());
        Assertions.assertTrue(results.get(1).getContent().contains("answer two"),
            results.get(1).getContent());
    }
}
