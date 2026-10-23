package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * Declaration of one callable tool: name, human description and a JSON-Schema
 * describing the arguments.
 */
public final class ToolSpec {

    private final String name;
    private final String description;
    private final JsonObject parametersSchema;

    /**
     * @param theName tool name, must match {@code [a-zA-Z0-9_]+}
     * @param theDescription description shown to the model
     * @param theParametersSchema JSON-Schema of the arguments object
     */
    public ToolSpec(final String theName, final String theDescription, final JsonObject theParametersSchema) {
        if(theName == null || !theName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("illegal tool name: " + theName);
        }
        name = theName;
        description = theDescription == null ? "" : theDescription;
        parametersSchema = theParametersSchema == null ? defaultSchema() : theParametersSchema;
    }

    /**
     * @return tool name
     */
    public String getName() {
        return name;
    }

    /**
     * @return description shown to the model
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return JSON-Schema of the arguments
     */
    public JsonObject getParametersSchema() {
        return parametersSchema;
    }

    /**
     * Wraps specs into the provider-neutral tool list shape.
     *
     * @param theSpecs tools to advertise
     * @return JSON array ready to be embedded into a request body
     */
    public static List<JsonObject> toToolArray(final List<ToolSpec> theSpecs) {
        final List<JsonObject> tools = new ArrayList<>();
        for(final ToolSpec spec : theSpecs) {
            final JsonObject function = new JsonObject();
            function.addProperty("name", spec.getName());
            function.addProperty("description", spec.getDescription());
            function.add("parameters", spec.getParametersSchema());
            final JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", function);
            tools.add(wrapper);
        }
        return Collections.unmodifiableList(tools);
    }

    private static JsonObject defaultSchema() {
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }
}
