package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ToolCall;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Fan-out of read-only tool calls. A model that decides several file reads
 * or searches in one round asks for calls that cannot observe each other:
 * running them one after another only adds their latencies. The loop must
 * run such calls at the same time while calls that may write stay strictly
 * serial, and the results still feed back in call order. Synchronization
 * proves real overlap without trusting wall-clock timings: a barrier tool
 * names whether a peer joined it within its budget.
 */
class AgentLoopParallelExecutionTest {

    /** Barrier budget of one tool; the test serial path must hit it fast. */
    private static final long BARRIER_MS = 300L;

    // fixture tool names and the overlap marker repeated across the scenarios
    private static final String OVERLAPPED = "overlapped";
    private static final String WRITE_FILE = "write_file";
    private static final String SEARCH_CODE = "search_code";
    private static final String LIST_FILES = "list_files";

    /**
     * Serves the scripted list on each call and records requests.
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

    /**
     * Read-only tool reporting whether a second concurrent call joined it.
     * Instances can share one counter to act as mutual barriers.
     */
    private static class BarrierTool implements IToolExecutor {

        private final String ourName;
        private final AtomicInteger waiting;
        private final boolean ourParallelSafe;

        BarrierTool(final String theName, final boolean theParallelSafe) {
            this(theName, theParallelSafe, new AtomicInteger());
        }

        BarrierTool(final String theName, final boolean theParallelSafe,
            final AtomicInteger theSharedCounter) {
            ourName = theName;
            ourParallelSafe = theParallelSafe;
            waiting = theSharedCounter;
        }

        @Override
        public String getName() {
            return ourName;
        }

        @Override
        public boolean isParallelSafe() {
            return ourParallelSafe;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(ourName, "waits for one concurrent peer", null);
        }

        @Override
        public String execute(final String theArgumentsJson) throws ToolExecutionException {
            if(waiting.incrementAndGet() >= 2) {
                return OVERLAPPED;
            }
            final long deadline = System.currentTimeMillis() + BARRIER_MS;
            try {
                while(waiting.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10L);
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ToolExecutionException("interrupted while waiting for a peer", ex);
            }
            if(waiting.get() >= 2) {
                return OVERLAPPED;
            }
            // leaving without a peer must not let a later call count this
            // departed waiter as its overlap partner
            waiting.decrementAndGet();
            return "waited alone";
        }
    }

    /**
     * Tool appending to one shared list without any locking: safe results
     * only exist when calls never touch it at the same time.
     */
    private static final class UnsafeTool implements IToolExecutor {

        @Override
        public String getName() {
            return WRITE_FILE;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(WRITE_FILE, "writes", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            return "wrote";
        }
    }

    private static ChatResult toolRound(final ToolCall... theCalls) {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1, List.of(theCalls));
    }

    private static ChatResult finalRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    private static ToolCall call(final String theId, final String theName) {
        return new ToolCall(theId, theName, "{}");
    }

    @Test
    @DisplayName("two parallel-safe calls in one round actually overlap")
    void parallelSafeCallsOverlap() throws Exception {
        final AtomicInteger barrier = new AtomicInteger();
        final BarrierTool first = new BarrierTool(SEARCH_CODE, true, barrier);
        final BarrierTool second = new BarrierTool(LIST_FILES, true, barrier);
        final ScriptedProvider provider = new ScriptedProvider(
            toolRound(call("t1", SEARCH_CODE), call("t2", LIST_FILES)),
            finalRound("done"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(first, second), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals("done", answer.getText());
        final List<String> results = toolResultsOf(provider.seen.get(1));
        Assertions.assertEquals(List.of(OVERLAPPED, OVERLAPPED), results,
            "each read-only call must have found a live peer, not waited alone");
    }

    @Test
    @DisplayName("a round containing a write-capable call stays strictly serial")
    void writeCapableRoundStaysSerial() throws Exception {
        final BarrierTool safeTool = new BarrierTool("read_file", true);
        final ScriptedProvider provider = new ScriptedProvider(
            toolRound(call("t1", "read_file"), call("t2", WRITE_FILE)),
            finalRound("done"));
        new AgentLoop(provider, List.of(safeTool, new UnsafeTool()), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        final List<String> results = toolResultsOf(provider.seen.get(1));
        Assertions.assertEquals(List.of("waited alone", "wrote"), results,
            "one unsafe call in the round must serialize the whole round");
    }

    @Test
    @DisplayName("parallel results still feed back in call order")
    void parallelResultsFeedBackInCallOrder() throws Exception {
        final AtomicInteger barrier = new AtomicInteger();
        final BarrierTool slow = new BarrierTool(SEARCH_CODE, true, barrier) {

            @Override
            public String execute(final String theArgumentsJson) throws ToolExecutionException {
                try {
                    Thread.sleep(150L);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ToolExecutionException("interrupted before the barrier", ex);
                }
                return super.execute(theArgumentsJson);
            }
        };
        final BarrierTool fast = new BarrierTool(LIST_FILES, true, barrier);
        final ScriptedProvider provider = new ScriptedProvider(
            toolRound(call("c1", SEARCH_CODE), call("c2", LIST_FILES)),
            finalRound("done"));
        new AgentLoop(provider, List.of(slow, fast), "stub", 5,
            new CancellationFlag()).run("sys", "task");

        final List<ChatMessage> messages = provider.seen.get(1).getMessages();
        final List<String> ids = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        for(final ChatMessage message : messages) {
            if("tool".equals(message.getRole())) {
                ids.add(message.getToolCallId());
                results.add(message.getContent());
            }
        }
        Assertions.assertEquals(List.of("c1", "c2"), ids,
            "the conversation must list tool results in the order the model asked for them");
        Assertions.assertEquals(List.of(OVERLAPPED, OVERLAPPED), results);
    }

    /**
     * @param theRequest a recorded request
     * @return contents of the tool-result messages in order
     */
    private static List<String> toolResultsOf(final ChatRequest theRequest) {
        final List<String> results = new ArrayList<>();
        for(final ChatMessage message : theRequest.getMessages()) {
            if("tool".equals(message.getRole())) {
                results.add(message.getContent());
            }
        }
        return results;
    }
}
