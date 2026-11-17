package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mad.edt.colleague.agent.PlanBoard.Status;
import com.mad.edt.colleague.agent.PlanBoard.Step;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * The plan tool handed to the model: one call replaces the whole visible
 * plan, and the tool answers with the board as rendered now, so the model
 * sees the effect of its own bookkeeping. The whole call is validated before
 * anything changes: a malformed plan never leaves half an approach on the
 * board.
 */
public final class PlanTool implements IToolExecutor {

    /** Tool id the plan board is addressed by. */
    public static final String TOOL_ID = "update_plan";

    private final PlanBoard board;

    /**
     * @param theBoard board this call owns; the model replaces its whole plan
     */
    public PlanTool(final PlanBoard theBoard) {
        board = theBoard;
    }

    @Override
    public String getName() {
        return TOOL_ID;
    }

    @Override
    public boolean isParallelSafe() {
        // the board is shared visible state: two plans applied at once
        // would race each other for it
        return false;
    }

    @Override
    public ToolSpec getSpec() {
        final JsonObject status = new JsonObject();
        status.addProperty("type", "string");
        final JsonArray accepted = new JsonArray();
        accepted.add("pending");
        accepted.add("active");
        accepted.add("done");
        status.add("enum", accepted);

        final JsonObject entryProperties = new JsonObject();
        final JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        entryProperties.add(CONTENT, content);
        entryProperties.add("status", status);

        final JsonObject entry = new JsonObject();
        entry.addProperty("type", "object");
        entry.add("properties", entryProperties);
        final JsonArray required = new JsonArray();
        required.add(CONTENT);
        entry.add("required", required);

        final JsonObject items = new JsonObject();
        items.addProperty("type", "array");
        items.add(ITEMS, entry);

        final JsonObject properties = new JsonObject();
        properties.add(ITEMS, items);

        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        final JsonArray topRequired = new JsonArray();
        topRequired.add(ITEMS);
        schema.add("required", topRequired);

        return new ToolSpec(TOOL_ID,
            "Replace your whole visible task plan with this list. Give every"
                + " unfinished task one status: pending, active (exactly one"
                + " at a time) or done. Call it before starting a multi-step"
                + " task and again whenever a step finishes.", schema);
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
            return ITEMS_ERROR;
        }
        final JsonElement items = parsed.getAsJsonObject().get(ITEMS);
        if(items == null || !items.isJsonArray() || items.getAsJsonArray().isEmpty()) {
            return ITEMS_ERROR;
        }
        final List<Step> plan = new ArrayList<>();
        for(final JsonElement entry : items.getAsJsonArray()) {
            final String content = entry.isJsonObject() ? text(entry.getAsJsonObject(), CONTENT) : null;
            if(content == null || content.isBlank()) {
                return "ERROR: every step needs a non-empty content";
            }
            final String rawStatus = text(entry.getAsJsonObject(), "status");
            Status status = Status.PENDING;
            if(rawStatus != null && !rawStatus.isBlank()) {
                status = toStatus(rawStatus);
                if(status == null) {
                    return "ERROR: unknown status '" + rawStatus
                        + "' (accepted: pending, active, done)";
                }
            }
            plan.add(new Step(content.trim(), status));
        }
        board.replace(plan);
        return board.render();
    }

    private static Status toStatus(final String theRaw) {
        switch(theRaw.trim().toLowerCase(Locale.ROOT)) {
            case "pending":
                return Status.PENDING;
            case "active":
                return Status.ACTIVE;
            case "done":
                return Status.DONE;
            default:
                return null;
        }
    }

    private static String text(final JsonObject theObject, final String theKey) {
        final JsonElement value = theObject.get(theKey);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static final String ITEMS_ERROR = "ERROR: items must be a non-empty array of {content, status} objects";

    /** Argument field names of the plan tool schema and payload. */
    private static final String ITEMS = "items";
    private static final String CONTENT = "content";
}
