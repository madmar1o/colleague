package com.mad.edt.colleague.edit;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * A model-written edit script: the workspace-relative path of the file to
 * edit plus an ordered list of search/replace pairs. Parsing reports clear
 * field-level errors so the model can repair its own script instead of
 * crashing the tool call.
 */
public final class EditScript {

    /**
     * Either the parsed script or a message that tells the model what to fix.
     */
    public static final class ParseResult {

        private final EditScript script;
        private final String error;

        ParseResult(final EditScript theScript, final String theError) {
            script = theScript;
            error = theError;
        }

        /**
         * @return {@code true} when {@link #getScript()} is usable
         */
        public boolean isOk() {
            return error == null;
        }

        /**
         * @return the parsed script, {@code null} on refusal
         */
        public EditScript getScript() {
            return script;
        }

        /**
         * @return what is wrong with the input, {@code null} on success
         */
        public String getError() {
            return error;
        }
    }

    private final String path;
    private final List<EditBlock> blocks;

    private EditScript(final String thePath, final List<EditBlock> theBlocks) {
        path = thePath;
        blocks = List.copyOf(theBlocks);
    }

    /**
     * Parses the JSON object of a tool call into a script.
     *
     * @param theJson text like {@code {"path":"a.bsl","edits":[{"search":"x","replace":"y"}]}}
     * @return the script or a field-level refusal message
     */
    public static ParseResult parse(final String theJson) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(theJson == null ? "" : theJson);
            if (!parsed.isJsonObject()) {
                return new ParseResult(null, "Expected a JSON object with \"path\" and \"edits\".");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException failure) {
            return new ParseResult(null, "Input is not valid JSON: " + failure.getMessage());
        }
        if (!isNonEmptyString(root.get("path"))) {
            return new ParseResult(null, "Missing \"path\": name the file to edit.");
        }
        JsonElement edits = root.get("edits");
        if (edits == null || !edits.isJsonArray()) {
            return new ParseResult(null, "Missing \"edits\": give a list of search/replace blocks.");
        }
        JsonArray editList = edits.getAsJsonArray();
        if (editList.isEmpty()) {
            return new ParseResult(null, "\"edits\" is empty: nothing to change.");
        }
        List<EditBlock> blocks = new ArrayList<>();
        for (int index = 0; index < editList.size(); index++) {
            JsonElement element = editList.get(index);
            if (!element.isJsonObject()) {
                return new ParseResult(null, "Edit block " + (index + 1) + " is not a JSON object.");
            }
            JsonObject block = element.getAsJsonObject();
            if (!block.has("search") || !block.get("search").isJsonPrimitive()) {
                return new ParseResult(null, "Edit block " + (index + 1)
                    + " is missing the \"search\" text: copy the exact fragment to replace.");
            }
            if (!block.has("replace") || !block.get("replace").isJsonPrimitive()) {
                return new ParseResult(null, "Edit block " + (index + 1)
                    + " is missing the \"replace\" text: give the new text to put in its place.");
            }
            blocks.add(new EditBlock(block.get("search").getAsString(),
                block.get("replace").getAsString()));
        }
        return new ParseResult(new EditScript(root.get("path").getAsString(), blocks), null);
    }

    private static boolean isNonEmptyString(final JsonElement theElement) {
        return theElement != null && theElement.isJsonPrimitive()
            && theElement.getAsJsonPrimitive().isString()
            && !theElement.getAsString().isBlank();
    }

    /**
     * @return workspace-relative path of the file to edit
     */
    public String getPath() {
        return path;
    }

    /**
     * @return requested changes in application order
     */
    public List<EditBlock> getBlocks() {
        return blocks;
    }
}
