package com.mad.edt.colleague.agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Transport contract of agent round-trips. A local model spends minutes on
 * one reasoning-heavy turn: a call whose budget covers the whole generated
 * answer gives up on a healthy server mid-generation, while a streamed call
 * bounded only by its first frame waits as long as the model needs. The loop
 * must therefore drive the provider through its streaming path.
 */
class AgentLoopStreamingTest {

    /**
     * Endpoint distinguishing the streaming path from the buffered one.
     */
    private static class StreamingProvider implements ILLMProvider {

        final AtomicInteger bufferedCalls = new AtomicInteger();
        final AtomicInteger streamingCalls = new AtomicInteger();
        final AtomicBoolean sawDeltaCallback = new AtomicBoolean();

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
            bufferedCalls.incrementAndGet();
            return answer();
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
            final Consumer<String> theOnThinking) {
            streamingCalls.incrementAndGet();
            theOnContent.accept("partial ");
            sawDeltaCallback.set(true);
            return answer();
        }

        private ChatResult answer() {
            return new ChatResult("final answer", null, "stub", "stop", 1, 1, List.of());
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
    @DisplayName("round-trips are streamed, not buffered")
    void roundTripsAreStreamed() throws Exception {
        final StreamingProvider provider = new StreamingProvider();
        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "stub", 3, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals("final answer", answer.getText());
        Assertions.assertEquals(1, provider.streamingCalls.get(),
            "agent turns outlive any whole-answer budget: only a first-frame-bounded call fits");
        Assertions.assertEquals(0, provider.bufferedCalls.get(),
            "the buffered path must not be reached once a provider streams");
    }

    @Test
    @DisplayName("a provider without streaming still serves the loop")
    void nonStreamingProviderStillWorks() throws Exception {
        final ILLMProvider plain = new ILLMProvider() {

            @Override
            public String getId() {
                return "plain";
            }

            @Override
            public ProviderConfig getConfig() {
                return null;
            }

            @Override
            public ChatResult chat(final ChatRequest theRequest) {
                return new ChatResult("done", null, "plain", "stop", 1, 1, List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of("plain");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        final AgentAnswer answer =
            new AgentLoop(plain, List.of(), "plain", 2, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals("done", answer.getText());
    }
}
