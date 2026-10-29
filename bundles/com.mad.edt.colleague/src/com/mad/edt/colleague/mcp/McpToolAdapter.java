package com.mad.edt.colleague.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * One discovered remote tool wearing the agent's own tool contract, so the
 * loop dispatches it exactly like a workspace tool. The advertised name is
 * prefixed with the server name: a remote tool can never shadow a built-in
 * one, and the model sees which server a name belongs to. A tool the server
 * did not declare read-only is treated as a writer, and the user confirms
 * each of its calls before a byte leaves the machine.
 */
public final class McpToolAdapter implements IToolExecutor {

    /**
     * Asks the person to let one write-like remote call through.
     */
    public interface Confirmation {

        /**
         * @param theQuestion what is about to happen, shown verbatim
         * @return the answer text; anything but a clear yes refuses
         */
        String ask(String theQuestion);
    }

    /** Prefix every remote name gets; keeps the catalog collision-free. */
    public static final String NAME_PREFIX = "mcp_";

    private final String serverName;
    private final ToolBridge bridge;
    private final McpToolInfo tool;
    private final Confirmation confirmation;
    private final String advertisedName;

    /**
     * @param theServerName server name as configured, used in the prefix
     * @param theBridge live (or scripted) connection to that server
     * @param theTool the tool as {@code tools/list} reported it
     * @param theConfirmation who to ask before a write-like call runs
     */
    public McpToolAdapter(final String theServerName, final ToolBridge theBridge,
        final McpToolInfo theTool, final Confirmation theConfirmation) {
        serverName = theServerName;
        bridge = theBridge;
        tool = theTool;
        confirmation = theConfirmation;
        advertisedName = NAME_PREFIX + safe(theServerName) + "_" + safe(tool.name());
    }

    @Override
    public String getName() {
        return advertisedName;
    }

    @Override
    public ToolSpec getSpec() {
        final StringBuilder description = new StringBuilder();
        description.append("[remote tool of server ").append(serverName).append("] ");
        description.append(tool.description() == null ? "" : tool.description());
        if(!tool.readOnly()) {
            description.append(" The user confirms every call of this tool before it runs.");
        }
        return new ToolSpec(advertisedName, description.toString(), tool.inputSchema());
    }

    @Override
    public boolean isParallelSafe() {
        return tool.readOnly();
    }

    @Override
    public String execute(final String theArgumentsJson) {
        final JsonObject arguments = parseArguments(theArgumentsJson);
        if(!tool.readOnly()) {
            final String answer = confirmation.ask("Tool " + advertisedName + " calls remote tool "
                + tool.name() + " on server " + serverName + " with arguments " + arguments
                + ". Allow this call? (yes/no)");
            if(answer == null || !answer.trim().toLowerCase().startsWith("y")) {
                return "ERROR the user refused this call; the server never saw it. "
                    + "Do not retry it with different arguments.";
            }
        }
        try {
            final String text = bridge.call(tool.name(), arguments);
            return text == null || text.isEmpty() ? "(the tool returned no text)" : text;
        } catch (final Exception ex) {
            final String reason = ex.getMessage() == null
                ? ex.getClass().getSimpleName() : ex.getMessage();
            return "ERROR remote call failed: " + reason;
        }
    }

    /**
     * @param theServerName configured server name to sanitize
     * @return the name lowercased to {@code [a-z0-9_]} for tool identifiers
     */
    public static String safe(final String theServerName) {
        return theServerName == null ? "server"
            : theServerName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * @param theArgumentsJson raw arguments text from the model
     * @return the arguments object, empty when the model sent nothing usable
     */
    private static JsonObject parseArguments(final String theArgumentsJson) {
        if(theArgumentsJson == null || theArgumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(theArgumentsJson).getAsJsonObject();
        } catch (final RuntimeException ex) {
            return new JsonObject();
        }
    }
}
