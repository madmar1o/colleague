package com.mad.edt.colleague.chat;

import com.mad.edt.colleague.ColleagueLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.llm.ChatMessage;

/**
 * The durable memory of every chat tab: the dialog of one tab (a provider
 * and contact pair) is stored under its key in one JSON document, so closing
 * a tab or restarting the workbench no longer erases what the conversation
 * already worked through. The store only remembers the dialog itself - the
 * user and assistant texts plus the compaction digest that stands in for the
 * folded-away turns; the throwaway per-turn context notes and the tool-call
 * plumbing of an agent run are deliberately left out. Each save rewrites the
 * whole document through a temporary file and an atomic rename: a crash or a
 * full disk costs at most the last save, never a half-written transcript the
 * next start would choke on.
 */
public final class ConversationStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Document property holding the transcripts keyed by tab. */
    private static final String KEY_SESSIONS = "sessions";

    private final Path file;
    private final JsonObject sessions;

    /**
     * Opens the store over the given JSON file; a missing or unreadable file
     * starts an empty store, and the next save rewrites it into a working one.
     *
     * @param theFile transcript document, created on the first save
     */
    public ConversationStore(final Path theFile) {
        file = theFile;
        sessions = readDocument();
    }

    /**
     * Replaces the transcript of one tab with the current history and lands
     * it on disk atomically: the bytes are written to a sibling temporary
     * file first, then one rename swaps it in, so a reader - this process or
     * a later start - never sees a torn document.
     *
     * @param theKey tab key: provider id, NUL, model (and agent name), the
     *            same key the workbench uses to reuse a session
     * @param theHistory the conversation snapshot to keep; per-turn context
     *            notes and tool-call messages are filtered out here
     */
    public synchronized void save(final String theKey, final List<ChatMessage> theHistory) {
        final JsonArray transcript = new JsonArray();
        for(final ChatMessage message : theHistory) {
            if(!worthRemembering(message)) {
                continue;
            }
            final JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole());
            entry.addProperty("content", message.getContent());
            transcript.add(entry);
        }
        if(transcript.isEmpty()) {
            sessions.remove(theKey);
        } else {
            sessions.add(theKey, transcript);
        }
        persist();
    }

    /**
     * @param theKey tab key of the wanted transcript
     * @return the stored dialog of that tab oldest first, empty when nothing
     *         was stored for it; the history cap of a live conversation
     *         never trims what the store hands back
     */
    public synchronized List<ChatMessage> load(final String theKey) {
        final List<ChatMessage> loaded = new ArrayList<>();
        final JsonElement stored = sessions.get(theKey);
        if(stored == null || !stored.isJsonArray()) {
            return loaded;
        }
        for(final JsonElement element : stored.getAsJsonArray()) {
            if(!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            final String role = text(entry, "role");
            final String content = text(entry, "content");
            if(role == null || content == null) {
                continue;
            }
            // rows written by earlier builds may carry a blank turn: skip it
            // on the way out, an old file heals itself without a migration
            if(!ChatMessage.ROLE_SYSTEM.equals(role) && content.isBlank()) {
                continue;
            }
            loaded.add(replay(role, content));
        }
        return loaded;
    }

    /**
     * Drops the stored dialog of one tab, the "new chat" case: what the user
     * cleared from the screen must not come back on the next start.
     *
     * @param theKey tab key to erase
     */
    public synchronized void forget(final String theKey) {
        if(sessions.remove(theKey) != null) {
            persist();
        }
    }

    /**
     * Only the dialog itself is worth a restart: user and assistant texts
     * stay, the digest that replaced the folded-away turns stays, while the
     * per-turn workspace context (re-attached fresh every turn anyway) and
     * the tool-call plumbing of one agent run are dropped. A turn that said
     * nothing is not a memory either: a broken provider stream must not
     * leave a blank bubble in the durable transcript (the 2026-11-05 live
     * incident behind K2).
     *
     * @param theMessage message of a live conversation snapshot
     * @return {@code true} when this message belongs in the stored transcript
     */
    private static boolean worthRemembering(final ChatMessage theMessage) {
        final String content = theMessage.getContent();
        if(content == null || content.isBlank()) {
            return false;
        }
        if(ChatMessage.ROLE_USER.equals(theMessage.getRole())) {
            return true;
        }
        if(ChatMessage.ROLE_ASSISTANT.equals(theMessage.getRole())) {
            return !theMessage.hasToolCalls();
        }
        return ChatMessage.ROLE_SYSTEM.equals(theMessage.getRole())
            && content.startsWith(Conversation.DIGEST_MARKER);
    }

    /**
     * Rebuilds one stored message with the role it was kept under; the
     * digest comes back as the same system instruction the live conversation
     * used, so the model reads it exactly as before.
     *
     * @param theRole stored role
     * @param theContent stored text
     * @return the message to seed the restored history with
     */
    private static ChatMessage replay(final String theRole, final String theContent) {
        if(ChatMessage.ROLE_USER.equals(theRole)) {
            return ChatMessage.user(theContent);
        }
        if(ChatMessage.ROLE_ASSISTANT.equals(theRole)) {
            return ChatMessage.assistant(theContent);
        }
        return ChatMessage.system(theContent);
    }

    private JsonObject readDocument() {
        try {
            if(Files.exists(file)) {
                final JsonElement parsed =
                    JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if(parsed.isJsonObject()) {
                    final JsonObject document = parsed.getAsJsonObject();
                    final JsonElement stored = document.get(KEY_SESSIONS);
                    if(stored != null && stored.isJsonObject()) {
                        return stored.getAsJsonObject();
                    }
                }
            }
        } catch (final IOException | RuntimeException ex) {
            // an unreadable document starts empty: losing the transcripts must
            // not cost the chat itself, and the next save rewrites the file
            ColleagueLog.error("[colleague] transcripts unreadable, starting empty: "
                + ex.getMessage());
        }
        return new JsonObject();
    }

    private void persist() {
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            final Path parent = file.getParent();
            if(parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary,
                GSON.toJson(composeDocument()), StandardCharsets.UTF_8);
            moveIntoPlace(temporary);
        } catch (final IOException ex) {
            // persistence is the first thing to give up, the in-memory
            // transcripts stay: losing the disk must not lose the session
            ColleagueLog.error("[colleague] transcripts not saved to disk: "
                + ex.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException ignored) {
                // the leftover temp file costs a little disk, nothing else
            }
        }
    }

    /**
     * Replaces the stored document with the written temp file.
     *
     * @param theTemporary path of the written temp file
     * @throws IOException when the file system refuses both moves
     */
    private void moveIntoPlace(final Path theTemporary) throws IOException {
        try {
            Files.move(theTemporary, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ex) {
            // a file system without atomic renames still gets the whole
            // document in one replace: better than losing the transcript
            Files.move(theTemporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private JsonObject composeDocument() {
        final JsonObject document = new JsonObject();
        document.add(KEY_SESSIONS, sessions);
        return document;
    }

    private static String text(final JsonObject theEntry, final String theProperty) {
        final JsonElement value = theEntry.get(theProperty);
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
