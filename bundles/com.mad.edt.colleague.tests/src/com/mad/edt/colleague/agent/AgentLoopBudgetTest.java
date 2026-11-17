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
 * The shape of a turn whose step budget ran out mid-work. A bare
 * "budget exhausted" note throws away everything the executed tools already
 * learned, and the user cannot pick the work back up. Instead the loop closes
 * the turn with one tool-free round-trip that summarizes the partial state,
 * and the interrupted conversation travels inside the answer so the caller
 * can continue the very same exchange later.
 */
class AgentLoopBudgetTest {

    private static final String READ_FILE = "read_file";

    /** serves the scripted rounds, repeating the last one, and records requests */
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
            return chatStream(theRequest, chunk -> { }, chunk -> { });
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

    /** a read-only tool the scripted rounds can call */
    private static final class StubTool implements IToolExecutor {

        @Override
        public String getName() {
            return READ_FILE;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(READ_FILE, "reads a file", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            return "file body";
        }
    }

    private static ChatResult toolRound() {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall("t1", READ_FILE, "{}")));
    }

    private static ChatResult textRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    @Test
    @DisplayName("a spent budget closes with one tool-free summary round-trip")
    void spentBudgetClosesWithToolFreeSummaryRound() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(toolRound(), toolRound(),
            textRound("done so far: two files read, deploy step left"));
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 2,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals(3, provider.seen.size(),
            "two budgeted tool rounds plus one closing round-trip");
        final ChatRequest closing = provider.seen.get(2);
        Assertions.assertTrue(closing.getTools().isEmpty(),
            "the closing round may not ask for more work: it must only summarize");
        Assertions.assertEquals(Boolean.FALSE, closing.getThink(),
            "the closing round skips the thinking pass, the budget for it is spent");

        Assertions.assertEquals("done so far: two files read, deploy step left",
            answer.getText());
        Assertions.assertFalse(answer.isCompleted(),
            "a summarized stop is still an unfinished task");
    }

    @Test
    @DisplayName("the exhausted answer carries the open conversation for a continuation")
    void exhaustedAnswerCarriesTheConversationForContinuation() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(toolRound(), toolRound(),
            textRound("closing summary"));
        final AgentAnswer stopped = new AgentLoop(provider, List.of(new StubTool()), "stub", 2,
            new CancellationFlag()).run("sys", "task");

        // system + user + (assistant-with-call + tool result) per budgeted round
        Assertions.assertEquals(6, stopped.getConversation().size());
        Assertions.assertEquals("tool", stopped.getConversation().get(5).getRole(),
            "the carried conversation ends on the last tool result, ready to continue");

        final ScriptedProvider nextRound = new ScriptedProvider(textRound("finished later"));
        final AgentAnswer resumed = new AgentLoop(nextRound, List.of(new StubTool()), "stub", 4,
            new CancellationFlag()).run(stopped.getConversation());
        Assertions.assertEquals("finished later", resumed.getText());
        Assertions.assertTrue(resumed.isCompleted());
    }

    @Test
    @DisplayName("the default budget is twelve round-trips")
    void defaultBudgetIsTwelveRoundTrips() throws Exception {
        final ScriptedProvider provider = new ScriptedProvider(toolRound());
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 0,
            new CancellationFlag()).run("sys", "task");

        Assertions.assertEquals(12, answer.getSteps());
        Assertions.assertEquals(13, provider.seen.size(),
            "twelve budgeted rounds plus one closing attempt");
        Assertions.assertFalse(answer.isCompleted());
        // the closing round here also asked for a call, so no summary text
        // came out and the exhaustion note stands - and it counts to twelve
        Assertions.assertTrue(answer.getText().contains("12"), answer.getText());
    }

    @Test
    @DisplayName("a stopped turn never runs the closing round-trip")
    void stoppedTurnSkipsTheClosingRoundTrip() throws Exception {
        final CancellationFlag flag = new CancellationFlag();
        flag.cancel();
        final ScriptedProvider provider = new ScriptedProvider(toolRound());
        final AgentAnswer answer = new AgentLoop(provider, List.of(new StubTool()), "stub", 2,
            flag).run("sys", "task");

        Assertions.assertTrue(provider.seen.isEmpty(),
            "the user stopped this turn: not one more model call may follow");
        Assertions.assertTrue(answer.getText().isEmpty());
        Assertions.assertFalse(answer.isCompleted());
    }

    @Test
    @DisplayName("the closing round-trip speaks to the observer like any final round")
    void closingRoundTripIsVisibleToTheObserver() throws Exception {
        final List<int[]> rounds = new ArrayList<>();
        final ScriptedProvider provider = new ScriptedProvider(toolRound(), toolRound(),
            textRound("closing summary"));
        final AgentLoop loop = new AgentLoop(provider, List.of(new StubTool()), "stub", 2,
            new CancellationFlag(), new AgentObserver() {
                @Override
                public void onRound(final int theStep, final int theRequestedToolCalls) {
                    rounds.add(new int[] {theStep, theRequestedToolCalls});
                }
            });
        loop.run("sys", "task");

        Assertions.assertEquals(List.of(1, 2, 3),
            rounds.stream().map(round -> Integer.valueOf(round[0])).toList());
        Assertions.assertEquals(0, Integer.valueOf(rounds.get(2)[1]),
            "the closing round requests no tools");
    }
}
