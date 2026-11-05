package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mad.edt.colleague.llm.ChatMessage;

/**
 * Pins how transcripts survive restarts: a dialog saved under a tab key
 * (provider plus contact) loads back through a fresh store over the same
 * file, the compaction digest rides along while the throwaway per-turn
 * context notes do not, the history cap never trims what the store keeps,
 * and every save lands on disk through one atomic file - never a half
 * written one and never a leftover temporary file.
 */
class ConversationStoreTest {

    /** Tab key of one contact: provider id, NUL, model name. */
    private static final String TAB_KEY = "srata\u0000qwen3:8b";

    @TempDir
    Path stateArea;

    private Path storeFile() {
        return stateArea.resolve("chat-sessions.json");
    }

    private static List<ChatMessage> dialog() {
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("первый вопрос"));
        messages.add(ChatMessage.assistant("первый ответ"));
        messages.add(ChatMessage.user("второй вопрос"));
        messages.add(ChatMessage.assistant("второй ответ"));
        return messages;
    }

    @Test
    void dialogSavedUnderTabKeyLoadsBackThroughAFreshStore() {
        final ConversationStore store = new ConversationStore(storeFile());
        store.save(TAB_KEY, dialog());

        final ConversationStore reopened = new ConversationStore(storeFile());
        final List<ChatMessage> loaded = reopened.load(TAB_KEY);

        assertEquals(4, loaded.size());
        assertEquals(ChatMessage.ROLE_USER, loaded.get(0).getRole());
        assertEquals("первый вопрос", loaded.get(0).getContent());
        assertEquals(ChatMessage.ROLE_ASSISTANT, loaded.get(1).getRole());
        assertEquals("первый ответ", loaded.get(1).getContent());
        assertEquals("второй вопрос", loaded.get(2).getContent());
        assertEquals("второй ответ", loaded.get(3).getContent());
    }

    @Test
    void savingTheSameTabAgainReplacesItsTranscriptNotDuplicatesIt() {
        final ConversationStore store = new ConversationStore(storeFile());
        store.save(TAB_KEY, dialog());
        store.save(TAB_KEY, List.of(ChatMessage.user("новый диалог")));

        final List<ChatMessage> loaded = new ConversationStore(storeFile()).load(TAB_KEY);
        assertEquals(1, loaded.size());
        assertEquals("новый диалог", loaded.get(0).getContent());
    }

    @Test
    void differentTabsKeepTheirOwnTranscriptsSideBySide() {
        final ConversationStore store = new ConversationStore(storeFile());
        store.save("srata\u0000qwen3:8b", List.of(ChatMessage.user("вкладку A")));
        store.save("srata\u0000llama3.1:8b", List.of(ChatMessage.user("вкладку B")));

        assertEquals("вкладку A", store.load("srata\u0000qwen3:8b").get(0).getContent());
        assertEquals("вкладку B", store.load("srata\u0000llama3.1:8b").get(0).getContent());
    }

    @Test
    void theCompactionDigestSurvivesWhilePerTurnContextNotesDoNot() {
        final ConversationStore store = new ConversationStore(storeFile());
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(Conversation.DIGEST_MARKER + "\nQ: старая реплика"));
        messages.add(ChatMessage.system("Workspace context:\nогромный дамп проекта"));
        messages.add(ChatMessage.user("вопрос"));
        messages.add(ChatMessage.assistant("ответ"));
        store.save(TAB_KEY, messages);

        final List<ChatMessage> loaded = new ConversationStore(storeFile()).load(TAB_KEY);

        assertEquals(3, loaded.size());
        assertTrue(loaded.get(0).getContent().startsWith(Conversation.DIGEST_MARKER),
            "the digest is the memory of everything folded away, it must persist");
        assertEquals(ChatMessage.ROLE_USER, loaded.get(1).getRole());
        assertEquals(ChatMessage.ROLE_ASSISTANT, loaded.get(2).getRole());
    }

    @Test
    void theHistoryCapNeverTrimsWhatTheStoreKeeps() {
        final List<ChatMessage> longDialog = new ArrayList<>();
        for(int index = 0; index < Conversation.MAX_HISTORY_MESSAGES * 2; index++) {
            longDialog.add(index % 2 == 0
                ? ChatMessage.user("вопрос " + index)
                : ChatMessage.assistant("ответ " + index));
        }
        new ConversationStore(storeFile()).save(TAB_KEY, longDialog);

        final List<ChatMessage> loaded = new ConversationStore(storeFile()).load(TAB_KEY);
        assertEquals(longDialog.size(), loaded.size(),
            "the store keeps the whole transcript; the cap belongs to the conversation");
        assertEquals("ответ 79", loaded.get(loaded.size() - 1).getContent());
    }

    @Test
    void forgettingATabLeavesNothingToRestore() {
        final ConversationStore store = new ConversationStore(storeFile());
        store.save(TAB_KEY, dialog());
        store.forget(TAB_KEY);

        assertTrue(new ConversationStore(storeFile()).load(TAB_KEY).isEmpty());
    }

    @Test
    void anAbsentKeyOrAMissingFileLoadsAnEmptyTranscript() {
        final ConversationStore missingFile = new ConversationStore(storeFile());
        assertTrue(missingFile.load(TAB_KEY).isEmpty());

        new ConversationStore(storeFile()).save(TAB_KEY, dialog());
        assertTrue(new ConversationStore(storeFile()).load("нет-такой\u0000вкладки").isEmpty());
    }

    @Test
    void everySaveLandsAtomicallyLeavingNoTemporaryFileBehind() throws IOException {
        final ConversationStore store = new ConversationStore(storeFile());
        store.save(TAB_KEY, dialog());
        store.save(TAB_KEY, List.of(ChatMessage.user("второй заход")));

        try (var files = Files.list(stateArea)) {
            final List<Path> leftovers = files
                .filter(path -> !storeFile().equals(path))
                .toList();
            assertTrue(leftovers.isEmpty(),
                "atomic write must rename the temporary file away, found: " + leftovers);
        }
        assertEquals("второй заход", new ConversationStore(storeFile()).load(TAB_KEY)
            .get(0).getContent());
    }

    @Test
    void aCorruptStoreFileStartsEmptyInsteadOfFailingTheChat() throws IOException {
        Files.writeString(storeFile(), "{this is not json");
        final ConversationStore store = new ConversationStore(storeFile());
        assertTrue(store.load(TAB_KEY).isEmpty());

        store.save(TAB_KEY, dialog());
        assertEquals(4, new ConversationStore(storeFile()).load(TAB_KEY).size(),
            "the first save rewrites the corrupt file into a working one");
    }

    /**
     * The live incident of 2026-11-05: a provider stream broke leaving an
     * assistant message with an empty text, and the store wrote the silent
     * turn into the durable file, where it surfaced as a blank bubble after
     * every restart. A turn that said nothing is not a memory: neither empty
     * answers nor empty questions belong in the stored transcript.
     */
    @Test
    void anEmptyTurnIsNotPersisted() {
        final ConversationStore store = new ConversationStore(storeFile());
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("вопрос"));
        messages.add(ChatMessage.assistant("нормальный ответ"));
        messages.add(ChatMessage.assistant(""));
        messages.add(ChatMessage.user("   "));
        store.save(TAB_KEY, messages);

        final List<ChatMessage> loaded = new ConversationStore(storeFile()).load(TAB_KEY);
        assertEquals(2, loaded.size(),
            "empty texts are not a memory either side of the exchange");
        assertEquals("вопрос", loaded.get(0).getContent());
        assertEquals("нормальный ответ", loaded.get(1).getContent());
    }

    /**
     * Transcripts saved by earlier builds may already carry the blank turn:
     * the loader skips it on the way out, so a same-day save/load cycle makes
     * an old file heal itself without a migration.
     */
    @Test
    void aStoredEmptyTurnIsSkippedOnLoad() throws IOException {
        final com.google.gson.JsonArray transcript = new com.google.gson.JsonArray();
        final com.google.gson.JsonObject user = new com.google.gson.JsonObject();
        user.addProperty("role", ChatMessage.ROLE_USER);
        user.addProperty("content", "вопрос");
        final com.google.gson.JsonObject assistant = new com.google.gson.JsonObject();
        assistant.addProperty("role", ChatMessage.ROLE_ASSISTANT);
        assistant.addProperty("content", "");
        transcript.add(user);
        transcript.add(assistant);
        final com.google.gson.JsonObject sessions = new com.google.gson.JsonObject();
        sessions.add(TAB_KEY, transcript);
        final com.google.gson.JsonObject document = new com.google.gson.JsonObject();
        document.add("sessions", sessions);
        Files.writeString(storeFile(), new com.google.gson.Gson().toJson(document));

        final List<ChatMessage> loaded = new ConversationStore(storeFile()).load(TAB_KEY);
        assertEquals(1, loaded.size(), "the blank assistant turn must not come back");
        assertEquals(ChatMessage.ROLE_USER, loaded.get(0).getRole());
        assertEquals("вопрос", loaded.get(0).getContent());
    }
}
