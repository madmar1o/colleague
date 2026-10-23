package com.mad.edt.colleague.llm;

/**
 * A tool invocation requested by the model in a chat completion.
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;

    /**
     * @param theId provider call identifier, echoed back in the tool result
     * @param theName tool name as advertised in the request
     * @param theArgumentsJson JSON object with call arguments, may be empty
     */
    public ToolCall(final String theId, final String theName, final String theArgumentsJson) {
        id = theId;
        name = theName;
        argumentsJson = theArgumentsJson == null ? "{}" : theArgumentsJson;
    }

    /**
     * @return provider call identifier, may be {@code null} for providers that
     *         do not assign one
     */
    public String getId() {
        return id;
    }

    /**
     * @return tool name
     */
    public String getName() {
        return name;
    }

    /**
     * @return raw JSON object of the arguments
     */
    public String getArgumentsJson() {
        return argumentsJson;
    }

    @Override
    public String toString() {
        return name + "(" + argumentsJson + ")";
    }
}
