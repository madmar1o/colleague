package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Pins the token-based side of the conversation compaction: a prompt that
 * would fill more than 70 percent of the model window is folded into the
 * digest before the request leaves for the endpoint, so the runtime never
 * has to cut the oldest turns away on its own, while a conversation that
 * comfortably fits the window is replayed untouched.
 */
class ConversationCompactByTokensTest {

    /** window the local provider is asked to keep ready by default */
    private static final int WINDOW_TOKENS = ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS;

    /** the percentage of the window a prompt may fill before compaction */
    private static final int COMPACT_AT_PERCENT = 70;

    /** records the request that actually leaves for the endpoint */
    private static final class RecordingProvider implements ILLMProvider {

        private final ProviderConfig myConfig = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
            .baseUrl("http://runtime.test")
            .defaultModel("test-model")
            .build();
        private final List<ChatRequest> myRequests = new ArrayList<>();

        List<ChatRequest> requests() {
            return myRequests;
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
            myRequests.add(theRequest);
            return new ChatResult("answer", "test-model", "stop", 1, 1);
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            myRequests.add(theRequest);
            theOnContent.accept("answer");
            return new ChatResult("answer", "test-model", "stop", 1, 1);
        }

        @Override
        public List<String> listModels() {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /**
     * @param thePrefix text put in front of the filler
     * @return a message long enough that a few exchanges fill the window
     */
    private static String longText(final String thePrefix) {
        return thePrefix + " " + "x".repeat(280);
    }

    /**
     * Mirrors the production character-based estimate for a sent request.
     *
     * @param theMessages messages of the request
     * @return estimated token count
     */
    private static int estimatedTokens(final List<ChatMessage> theMessages) {
        int characters = 0;
        for(final ChatMessage message : theMessages) {
            characters += message.getContent().length();
        }
        return characters * 3 / 5 + 256;
    }

    @Test
    void promptOverTheTokenThresholdIsCompactedBeforeSending() throws LLMException {
        final RecordingProvider provider = new RecordingProvider();
        final Conversation conversation = new Conversation(provider, "role instructions");
        for(int index = 0; index < 19; index++) {
            conversation.noteExchange(longText("question " + index), longText("answer " + index));
        }
        // the message-count cap alone leaves this history untouched, so the
        // compaction below can only come from the token estimate
        assertEquals(38, conversation.snapshotHistory().size());

        conversation.ask("test-model", null, "final question");

        final List<ChatMessage> sent = provider.requests().get(0).getMessages();
        // the request keeps the assistant instructions first and replays the
        // compacted history after them
        assertTrue(sent.get(1).getContent().startsWith(Conversation.DIGEST_MARKER),
            "the digest must lead the history, got: " + sent.get(1).getContent());
        assertTrue(sent.get(1).getContent().contains("Q: question 0"),
            "the folded turn must be remembered in the digest, got: " + sent.get(1).getContent());
        assertTrue(sent.get(1).getContent().contains("A: answer 0"),
            "both sides of the folded turn must be remembered");
        assertFalse(containsVerbatim(sent, longText("question 0")),
            "the folded question must not be replayed in full");
        // the newest turn still travels verbatim, it is what follow-up
        // questions are built on
        assertTrue(containsVerbatim(sent, longText("question 18")),
            "the newest question must stay verbatim");
        assertTrue(containsVerbatim(sent, longText("answer 18")),
            "the newest answer must stay verbatim");
        final int estimated = estimatedTokens(sent);
        assertTrue(estimated <= WINDOW_TOKENS * COMPACT_AT_PERCENT / 100,
            "the request must fit the token threshold, was " + estimated + " tokens");
    }

    @Test
    void smallPromptsAreReplayedUntouched() throws LLMException {
        final RecordingProvider provider = new RecordingProvider();
        final Conversation conversation = new Conversation(provider, "role instructions");
        for(int index = 0; index < 5; index++) {
            conversation.noteExchange("question " + index, "answer " + index);
        }

        conversation.ask("test-model", null, "final question");

        final List<ChatMessage> sent = provider.requests().get(0).getMessages();
        // system prompt + five exchanges + the fresh question, nothing folded
        assertEquals(12, sent.size());
        assertFalse(hasDigest(sent));
        assertTrue(containsVerbatim(sent, "question 0"));
        assertTrue(containsVerbatim(sent, "answer 4"));
    }

    /**
     * @param theMessages messages of the request
     * @return {@code true} when the list carries a compacted-history digest
     */
    private static boolean hasDigest(final List<ChatMessage> theMessages) {
        for(final ChatMessage message : theMessages) {
            if(message.getContent().startsWith(Conversation.DIGEST_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param theMessages messages of the request
     * @param theContent exact message text looked for
     * @return {@code true} when the text travels untouched
     */
    private static boolean containsVerbatim(final List<ChatMessage> theMessages, final String theContent) {
        for(final ChatMessage message : theMessages) {
            if(theContent.equals(message.getContent())) {
                return true;
            }
        }
        return false;
    }
}
