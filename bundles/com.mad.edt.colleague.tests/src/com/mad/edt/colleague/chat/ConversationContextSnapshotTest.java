package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Pins the context observability contract of spec 0023: after every finished
 * turn {@link Conversation} exposes a {@link ContextSnapshot} telling how
 * full the prompt was, which window the provider declares, how much history
 * is replayed, whether a digest exists, whether this very turn compacted it,
 * and whether the runtime cut the prompt on its own. The chat panel shows
 * these numbers to the user, so "the chat forgot" becomes a measurable fact.
 */
class ConversationContextSnapshotTest {

    private static final String MODEL = "test-model";
    private static final String ANSWER = "answer";

    /** endpoint whose window and stop reason the test picks */
    private static final class SnapshotProvider implements ILLMProvider {

        private final ProviderConfig myConfig;
        private final String myFinishReason;

        SnapshotProvider(final int theWindowTokens, final String theFinishReason) {
            myConfig = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test")
                .defaultModel(MODEL)
                .contextWindowTokens(theWindowTokens)
                .build();
            myFinishReason = theFinishReason;
        }

        @Override
        public String getId() {
            return "test";
        }

        @Override
        public ProviderConfig getConfig() {
            return myConfig;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) {
            return new ChatResult(ANSWER, MODEL, myFinishReason, 1, 1);
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            theOnContent.accept(ANSWER);
            return new ChatResult(ANSWER, MODEL, myFinishReason, 1, 1);
        }

        @Override
        public java.util.List<String> listModels() {
            return java.util.List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /**
     * @param thePrefix text put in front of the filler
     * @return a question long enough that a few exchanges fill a small window
     */
    private static String longQuestion(final String thePrefix) {
        return thePrefix + " " + "q".repeat(400);
    }

    @Test
    @DisplayName("BDD1: window 32768, five short exchanges: estimate under limit, window reported, no digest")
    void snapshotReportsWindowCountersAndFitsUnderTheLimit() throws LLMException {
        final Conversation conversation =
            new Conversation(new SnapshotProvider(32_768, "stop"), "say brief");
        for(int exchange = 0; exchange < 5; exchange++) {
            conversation.ask(MODEL, null, "question " + exchange, chunk -> { }, chunk -> { });
        }
        final ContextSnapshot snapshot = conversation.lastContextSnapshot();
        assertNotNull(snapshot, "a finished turn must leave a snapshot behind");
        assertEquals(32_768, snapshot.providerWindowTokens());
        assertEquals(32_768 * 70 / 100, snapshot.tokenLimit());
        assertEquals(10, snapshot.historyMessages(), "five exchanges stay verbatim");
        assertFalse(snapshot.hasDigest());
        assertFalse(snapshot.compactedThisTurn());
        assertTrue(snapshot.estimatedPromptTokens() <= snapshot.tokenLimit(),
            "a conversation that fits the window never needs compaction");
    }

    @Test
    @DisplayName("BDD2: a turn that folded the history marks compaction and a digest")
    void snapshotMarksTheTurnThatCompactedTheHistory() throws LLMException {
        final Conversation conversation =
            new Conversation(new SnapshotProvider(1_024, "stop"), "say brief");
        conversation.ask(MODEL, null, longQuestion("first"), chunk -> { }, chunk -> { });
        conversation.ask(MODEL, null, longQuestion("second"), chunk -> { }, chunk -> { });
        conversation.ask(MODEL, null, longQuestion("third"), chunk -> { }, chunk -> { });
        final ContextSnapshot snapshot = conversation.lastContextSnapshot();
        assertTrue(snapshot.compactedThisTurn(), "the third turn had to fold the oldest exchange");
        assertTrue(snapshot.hasDigest(), "a folded prefix survives as one digest message");
        assertEquals(5, snapshot.historyMessages(),
            "q1+a1 folded into the digest, the digest and the last exchange stay");
        assertTrue(snapshot.estimatedPromptTokens() > 0, "the estimate is always reported");
    }

    @Test
    @DisplayName("BDD3: a turn the runtime cut by the window reports the truncation")
    void snapshotCarriesTheRuntimeTruncationOfThisTurn() throws LLMException {
        final Conversation conversation =
            new Conversation(new SnapshotProvider(32_768, "length"), "say brief");
        conversation.ask(MODEL, null, "long question", chunk -> { }, chunk -> { });
        final ContextSnapshot snapshot = conversation.lastContextSnapshot();
        assertTrue(snapshot.runtimeTruncated());
        assertFalse(snapshot.compactedThisTurn());
    }
}
