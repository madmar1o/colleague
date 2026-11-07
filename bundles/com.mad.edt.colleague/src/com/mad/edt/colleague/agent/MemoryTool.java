package com.mad.edt.colleague.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * The remember tool: one short lesson the model judged worth keeping for
 * later sessions goes into the shared lesson memory right away, so the note
 * survives this conversation and shows up in the next one's instructions.
 */
public final class MemoryTool implements IToolExecutor {

    private final AgentMemory memory;

    /**
     * @param theMemory shared lesson memory the note is stored in
     */
    public MemoryTool(final AgentMemory theMemory) {
        memory = theMemory;
    }

    @Override
    public String getName() {
        return "remember";
    }

    @Override
    public boolean isParallelSafe() {
        // the lesson file is shared durable state: writes go one at a time
        return false;
    }

    @Override
    public ToolSpec getSpec() {
        final JsonObject note = new JsonObject();
        note.addProperty("type", "string");
        final JsonObject properties = new JsonObject();
        properties.add("note", note);
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);

        return new ToolSpec("remember",
            "Store one short lesson for later sessions on this project:"
                + " a quirk you discovered, an approach that worked or failed,"
                + " a rule the user corrected you on. One sentence, no code.", schema);
    }

    @Override
    public String execute(final String theArgumentsJson) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(theArgumentsJson);
        } catch (final JsonSyntaxException ex) {
            parsed = null;
        }
        if(parsed == null || !parsed.isJsonObject()) {
            return NOTE_ERROR;
        }
        final JsonElement value = parsed.getAsJsonObject().get("note");
        final String note = value == null || !value.isJsonPrimitive() ? null : value.getAsString();
        if(note == null || note.isBlank()) {
            return NOTE_ERROR;
        }
        if(!memory.addLesson(note)) {
            return "Already in the lesson memory, nothing new stored; it keeps "
                + memory.lessonCount() + plural(memory.lessonCount()) + ".";
        }
        final int kept = memory.lessonCount();
        return "Remembered. " + kept + plural(kept) + " kept for later sessions.";
    }

    private static String plural(final int theCount) {
        return theCount == 1 ? " lesson" : " lessons";
    }

    private static final String NOTE_ERROR = "ERROR: pass a non-empty note";
}
