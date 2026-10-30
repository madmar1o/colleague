package com.mad.edt.colleague.mcp;

import java.util.Arrays;
import java.util.List;

/**
 * A tool server the agent may borrow tools from: either a local process the
 * chat spawns and talks to over stdin/stdout, or an HTTP endpoint answering
 * JSON-RPC POSTs. The name keys the {@code mcp_<server>_<tool>} prefix and
 * the settings storage, so it has to stay stable across edits.
 */
public final class McpServerConfig {

    private final String name;
    private final String[] command;
    private final String url;
    private final boolean enabled;

    /**
     * @param theName server name used for tool prefixes, {@code [a-z0-9_]}
     * @param theCommand command line spawning a stdio server, {@code null}
     *            for an HTTP server
     * @param theUrl HTTP endpoint answering JSON-RPC, {@code null} for a
     *            stdio server
     * @param theEnabled whether the chat should consult this server at all
     */
    public McpServerConfig(final String theName, final List<String> theCommand,
        final String theUrl, final boolean theEnabled) {
        name = theName;
        command = theCommand == null ? null : theCommand.toArray(new String[0]);
        url = theUrl;
        enabled = theEnabled;
    }

    /** @return server name as stored and used in tool prefixes */
    public String getName() {
        return name;
    }

    /** @return command line of a stdio server, {@code null} for HTTP servers */
    public String[] getCommand() {
        return command == null ? null : command.clone();
    }

    /** @return HTTP endpoint, {@code null} for stdio servers */
    public String getUrl() {
        return url;
    }

    /** @return whether the server is switched on */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return whether this entry talks over HTTP rather than a child process */
    public boolean isHttp() {
        return url != null && !url.isEmpty();
    }

    /**
     * Value comparison for the catalog's reload check: two entries match when
     * every field that changes how the server is reached matches.
     *
     * @param theOther entry to compare against
     * @return whether both describe the same connection
     */
    public boolean sameSettings(final McpServerConfig theOther) {
        if (theOther == null || !name.equals(theOther.name)
            || enabled != theOther.enabled || !java.util.Objects.equals(url, theOther.url)
            || (command == null) != (theOther.command == null)) {
            return false;
        }
        return command == null || Arrays.equals(command, theOther.command);
    }
}
