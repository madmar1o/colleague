package com.mad.edt.colleague.mcp;

/**
 * Transport or protocol failure while talking to one MCP server. Callers in
 * the agent path convert this into an error result string, exactly like the
 * other tool failures; it never unwinds a whole chat turn.
 */
public class McpException extends Exception {

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
