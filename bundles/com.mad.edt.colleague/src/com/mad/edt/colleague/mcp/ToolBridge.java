package com.mad.edt.colleague.mcp;

import java.util.List;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.agent.ToolExecutionException;

/**
 * The two calls the adapter layer needs from a live tool server.
 * {@link McpClient} is the production implementation; tests substitute a
 * scripted stand-in to watch what the adapters send.
 */
public interface ToolBridge {

    /**
     * @return the tools the server exposes, handshake included
     * @throws ToolExecutionException when the server is unreachable or refuses
     */
    List<McpToolInfo> listTools() throws ToolExecutionException;

    /**
     * Invokes one remote tool.
     *
     * @param theName tool name as discovered
     * @param theArguments JSON object of arguments, {@code null} for none
     * @return concatenated text content of the result
     * @throws ToolExecutionException on transport or protocol failure
     */
    String call(String theName, JsonObject theArguments) throws ToolExecutionException;
}
