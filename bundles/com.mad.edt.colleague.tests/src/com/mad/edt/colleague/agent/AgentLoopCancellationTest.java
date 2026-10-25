package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Characterization tests for the cancellation contract of the ReAct loop:
 * the loop must poll the shared latch between steps and never call the
 * endpoint once the turn has been given up on.
 */
class AgentLoopCancellationTest {

    /**
     * Scripted endpoint: hands back the queued results and counts calls.
     */
    private static class StubProvider implements ILLMProvider {

        private final List<ChatResult> script = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

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
            final int index = calls.getAndIncrement();
            return index < script.size() ? script.get(index)
                : new ChatResult("done", "stub", "stop", 1, 1, List.of());
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
     * Tool that cancels the turn while it is being executed.
     */
    private static final class CancellingTool implements IToolExecutor {

        private final CancellationFlag flag;

        CancellingTool(final CancellationFlag theFlag) {
            flag = theFlag;
        }

        @Override
        public String getName() {
            return "noop";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("noop", "cancels the turn", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            flag.cancel();
            return "cancelled from inside the tool";
        }
    }

    @Test
    @DisplayName("a pre-cancelled loop never reaches the endpoint")
    void preCancelled() throws Exception {
        final StubProvider provider = new StubProvider();
        final CancellationFlag flag = new CancellationFlag();
        flag.cancel();
        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "stub", 4, flag).run("sys", "task");
        Assertions.assertEquals(0, provider.calls.get(),
            "no model round-trip may be started for a cancelled turn");
        Assertions.assertEquals("", answer.getText());
        Assertions.assertFalse(answer.isCompleted());
    }

    @Test
    @DisplayName("cancelling inside a tool stops the loop before the next round-trip")
    void cancelledMidLoop() throws Exception {
        final StubProvider provider = new StubProvider();
        final CancellationFlag flag = new CancellationFlag();
        provider.script.add(new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall("id-1", "noop", "{}"))));
        final AgentAnswer answer = new AgentLoop(provider,
            List.of(new CancellingTool(flag)), "stub", 4, flag).run("sys", "task");
        Assertions.assertEquals(1, provider.calls.get(),
            "the loop must not ask the model again after the turn was cancelled");
        Assertions.assertEquals(1, answer.getExecutedToolCalls());
        Assertions.assertFalse(answer.isCompleted());
    }

    @Test
    @DisplayName("every request the loop builds carries the loop latch")
    void latchPropagates() throws Exception {
        final CancellationFlag flag = new CancellationFlag();
        final List<ChatRequest> seen = new ArrayList<>();
        final ILLMProvider recording = new StubProvider() {
            @Override
            public ChatResult chat(final ChatRequest theRequest) {
                seen.add(theRequest);
                return super.chat(theRequest);
            }
        };
        new AgentLoop(recording, List.of(), "stub", 2, flag).run("sys", "task");
        Assertions.assertEquals(1, seen.size());
        Assertions.assertSame(flag, seen.get(0).getCancellation());
    }
}
