package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatMessage;

/**
 * Pins how a long conversation compacts itself instead of silently
 * forgetting: past the history cap the oldest turns fold into one digest
 * message kept at the front, every later compaction merges into that same
 * digest, the most recent turns stay verbatim, and the digest itself has a
 * size ceiling so a whole working day cannot blow up the prompt.
 */
class ConversationCompactionTest {

    private static Conversation fresh() {
        return new Conversation(null, "role instructions");
    }

    @Test
    void oldTurnsFoldIntoADigestInsteadOfVanishing() {
        final Conversation conversation = fresh();
        for(int index = 0; index < 21; index++) {
            conversation.noteExchange("question " + index, "answer " + index);
        }
        final List<ChatMessage> history = conversation.snapshotHistory();

        assertTrue(history.size() <= Conversation.MAX_HISTORY_MESSAGES,
            "history must stay capped, was " + history.size());
        assertFalse(history.isEmpty());
        assertTrue(history.get(0).getContent().startsWith(Conversation.DIGEST_MARKER));
        assertEquals(ChatMessage.ROLE_SYSTEM, history.get(0).getRole());
        // the very first exchange is remembered in the digest, not deleted
        assertTrue(history.get(0).getContent().contains("Q: question 0"));
        assertTrue(history.get(0).getContent().contains("A: answer 0"));
        // and the newest turn is still there verbatim
        assertTrue(containsMessage(history, ChatMessage.ROLE_USER, "question 20"));
        assertTrue(containsMessage(history, ChatMessage.ROLE_ASSISTANT, "answer 20"));
    }

    @Test
    void everyLaterCompactionMergesIntoTheSameDigest() {
        final Conversation conversation = fresh();
        for(int index = 0; index < 40; index++) {
            conversation.noteExchange("q" + index, "a" + index);
        }
        final List<ChatMessage> history = conversation.snapshotHistory();

        int digests = 0;
        for(final ChatMessage message : history) {
            if(message.getContent().startsWith(Conversation.DIGEST_MARKER)) {
                digests++;
            }
        }
        assertEquals(1, digests, "exchanges compact again and again, digests must merge");
        assertTrue(history.size() <= Conversation.MAX_HISTORY_MESSAGES);
    }

    @Test
    void theDigestItselfIsCappedSoPromptCostStaysBounded() {
        final Conversation conversation = fresh();
        for(int index = 0; index < 120; index++) {
            conversation.noteExchange("question number " + index
                + " about the settlement register and its totals",
                "answer number " + index + " explaining the recorder and the locks");
        }
        final List<ChatMessage> history = conversation.snapshotHistory();

        assertTrue(history.get(0).getContent().startsWith(Conversation.DIGEST_MARKER));
        // marker line plus the digest body ceiling, generously rounded
        assertTrue(history.get(0).getContent().length() <= 2_400,
            "digest grew to " + history.get(0).getContent().length() + " chars");
        assertTrue(history.size() <= Conversation.MAX_HISTORY_MESSAGES);
    }

    @Test
    void aResetClearsTheDigestTogetherWithTheHistory() {
        final Conversation conversation = fresh();
        for(int index = 0; index < 25; index++) {
            conversation.noteExchange("q" + index, "a" + index);
        }
        conversation.reset();

        assertEquals(0, conversation.snapshotHistory().size());
    }

    private static boolean containsMessage(final List<ChatMessage> theHistory,
        final String theRole, final String theContent) {
        for(final ChatMessage message : theHistory) {
            if(theRole.equals(message.getRole()) && theContent.equals(message.getContent())) {
                return true;
            }
        }
        return false;
    }
}
