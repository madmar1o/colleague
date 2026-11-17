package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.http.TransportFailure;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.TurnCancelledException;

/**
 * Resilience contract of the ReAct loop against flaky local runtimes: a model
 * server can drop just its generator process while keeping the serving port
 * alive, and restarts it on the very next request. The loop must pay one
 * repair attempt out of its own step budget for such failures, and never
 * re-send requests the endpoint rejected for a permanent reason.
 */
class AgentLoopRetryTest {

    // fixture answers repeated across the scenarios
    private static final String RECOVERED = "recovered";
    private static final String HTTP_500 = "returned HTTP 500";

    /**
     * Scripted endpoint: fails the first {@code failures} calls, answers after that.
     */
    private static class FlakyProvider implements ILLMProvider {

        private final List<LLMException> failures = new ArrayList<>();
        int calls;

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public ProviderConfig getConfig() {
            return null;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) throws LLMException {
            calls++;
            if(calls <= failures.size()) {
                throw failures.get(calls - 1);
            }
            return new ChatResult(RECOVERED, null, "stub", "stop", 1, 1, List.of());
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
    @DisplayName("a server-side failure gets exactly one repair attempt")
    void serverFailureGetsOneRepairAttempt() throws Exception {
        final FlakyProvider provider = new FlakyProvider();
        provider.failures.add(new LLMException(
            "Endpoint http://127.0.0.1:11434 returned HTTP 500:"
                + " {\"error\":\"read tcp: wsarecv: An existing connection was forcibly"
                + " closed by the remote host.\"}", 500, null));
        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "stub", 4, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals(RECOVERED, answer.getText());
        Assertions.assertEquals(2, provider.calls, "one failure, one repair call");
        Assertions.assertEquals(2, answer.getSteps(), "the repair attempt pays from the step budget");
    }

    @Test
    @DisplayName("a second failure ends the turn instead of retrying forever")
    void secondServerFailureEndsTheTurn() {
        final FlakyProvider provider = new FlakyProvider();
        provider.failures.add(new LLMException(HTTP_500, 500, null));
        provider.failures.add(new LLMException(HTTP_500, 500, null));
        provider.failures.add(new LLMException(HTTP_500, 500, null));
        final LLMException thrown = Assertions.assertThrows(LLMException.class,
            () -> new AgentLoop(provider, List.of(), "stub", 4, new CancellationFlag()).run("sys", "task"));
        Assertions.assertEquals(500, thrown.getStatusCode());
        Assertions.assertEquals(2, provider.calls, "the repair call must not open another retry chain");
    }

    @Test
    @DisplayName("a request the endpoint rejected is never re-sent")
    void clientErrorIsNotRepaired() {
        final FlakyProvider provider = new FlakyProvider();
        provider.failures.add(new LLMException("returned HTTP 404: model not found", 404, null));
        Assertions.assertThrows(LLMException.class,
            () -> new AgentLoop(provider, List.of(), "stub", 4, new CancellationFlag()).run("sys", "task"));
        Assertions.assertEquals(1, provider.calls, "4xx is permanent, a repair call only wastes the turn");
    }

    @Test
    @DisplayName("a dropped connection counts as repairable")
    void transportDropIsRepaired() throws Exception {
        final FlakyProvider provider = new FlakyProvider();
        provider.failures.add(new LLMException("cannot reach endpoint: connection reset",
            new TransportFailure("connection reset")));
        final AgentAnswer answer =
            new AgentLoop(provider, List.of(), "stub", 4, new CancellationFlag()).run("sys", "task");
        Assertions.assertEquals(RECOVERED, answer.getText());
        Assertions.assertEquals(2, provider.calls);
    }

    @Test
    @DisplayName("no repair attempt exists outside the step budget")
    void repairAttemptPaysFromTheStepBudget() {
        final FlakyProvider provider = new FlakyProvider();
        provider.failures.add(new LLMException(HTTP_500, 500, null));
        Assertions.assertThrows(LLMException.class,
            () -> new AgentLoop(provider, List.of(), "stub", 1, new CancellationFlag()).run("sys", "task"));
        Assertions.assertEquals(1, provider.calls,
            "a one-step budget must not be stretched into two round-trips");
    }

    @Test
    @DisplayName("a cancelled turn is unwound, not repaired")
    void cancellationIsNotRepaired() {
        final FlakyProvider provider = new FlakyProvider() {
            @Override
            public ChatResult chat(final ChatRequest theRequest) throws LLMException {
                calls++;
                throw new TurnCancelledException();
            }
        };
        Assertions.assertThrows(TurnCancelledException.class,
            () -> new AgentLoop(provider, List.of(), "stub", 4, new CancellationFlag()).run("sys", "task"));
        Assertions.assertEquals(1, provider.calls);
    }
}
