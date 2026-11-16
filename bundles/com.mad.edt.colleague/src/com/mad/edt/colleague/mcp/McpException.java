package com.mad.edt.colleague.mcp;

import com.mad.edt.colleague.agent.ToolExecutionException;

/**
 * Transport or protocol failure while talking to one MCP server. Callers in
 * the agent path convert this into an error result string, exactly like the
 * other tool failures; it never unwinds a whole chat turn. It is a kind of
 * {@link ToolExecutionException} so the tool contracts can name one checked
 * failure type.
 */
public class McpException extends ToolExecutionException {

    private static final long serialVersionUID = 1L;

    /**
     * @param theMessage what went wrong
     */
    public McpException(final String theMessage) {
        super(theMessage);
    }

    /**
     * @param theMessage what went wrong
     * @param theCause the underlying transport failure
     */
    public McpException(final String theMessage, final Throwable theCause) {
        super(theMessage, theCause);
    }
}
