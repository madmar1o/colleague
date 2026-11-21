package com.mad.edt.colleague.mcp;

import com.mad.edt.colleague.ColleagueLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.agent.ToolExecutionException;

/**
 * The one place the chat asks for remote tools, so every server shares a
 * single reload policy: the catalog rebuilds itself when the stored settings
 * change, an unreachable server just contributes nothing instead of breaking
 * the chat, and a remote name that collides with a built-in tool is dropped -
 * the built-in always wins.
 */
public final class McpCatalog {

    /** Discovers one server's tools; {@link McpClient#discover()} in production. */
    public interface Discovery {

        /**
         * @param theConfig server to ask
         * @return the tools that server exposes
         * @throws ToolExecutionException when the server is unreachable or refuses
         */
        McpToolInfo[] discover(McpServerConfig theConfig) throws ToolExecutionException;
    }

    /** Opens (or scripts) the call channel of one server. */
    public interface BridgeFactory {

        /**
         * @param theConfig server to connect to
         * @return the channel adapters will call tools through
         */
        ToolBridge create(McpServerConfig theConfig);
    }

    private final Discovery ourDiscovery;
    private final BridgeFactory ourBridgeFactory;
    private final Supplier<List<McpServerConfig>> ourConfigs;
    private final Map<String, ToolBridge> ourBridges = new LinkedHashMap<>();

    private List<McpServerConfig> ourSnapshot;
    private List<IToolExecutor> ourTools = List.of();

    /**
     * @param theDiscovery how to ask one server for its tools
     * @param theBridgeFactory how to open the call channel of one server
     * @param theConfigs where the current server list lives; called on every
     *            rebuild, so the page can change servers at any time
     */
    public McpCatalog(final Discovery theDiscovery, final BridgeFactory theBridgeFactory,
        final Supplier<List<McpServerConfig>> theConfigs) {
        ourDiscovery = theDiscovery;
        ourBridgeFactory = theBridgeFactory;
        ourConfigs = theConfigs;
    }

    /**
     * The production catalog: one live client per enabled server, settings
     * read from the workspace preferences on every rebuild.
     *
     * @return a catalog wired to {@link McpSettings} and real {@link McpClient}s
     */
    public static McpCatalog live() {
        final Map<String, McpClient> clients = new LinkedHashMap<>();
        return new McpCatalog(
            theConfig -> {
                final McpClient client = clients.computeIfAbsent(theConfig.getName(),
                    name -> new McpClient(theConfig));
                return client.discover().toArray(new McpToolInfo[0]);
            },
            theConfig -> clients.computeIfAbsent(theConfig.getName(),
                name -> new McpClient(theConfig)),
            McpSettings::loadServers);
    }

    /**
     * The remote tools to advertise this run, rebuilt only when the settings
     * actually changed; between rebuilds the cached adapters and their open
     * channels are reused.
     *
     * @param theReservedNames names the chat already offers itself; a remote
     *            tool losing to one of them is skipped, never renamed
     * @param theConfirmation who to ask before a write-like remote call runs
     * @return adapters for all enabled reachable servers, built-ins untouched
     */
    public synchronized List<IToolExecutor> tools(final Set<String> theReservedNames,
        final McpToolAdapter.Confirmation theConfirmation) {
        final List<McpServerConfig> current = ourConfigs.get();
        if (ourSnapshot != null && sameServers(ourSnapshot, current)) {
            return ourTools;
        }
        closeBridges();
        final Set<String> taken = new LinkedHashSet<>(theReservedNames);
        final List<IToolExecutor> tools = new ArrayList<>();
        for (final McpServerConfig config : current) {
            if (!config.isEnabled()) {
                continue;
            }
            final ToolBridge bridge = ourBridgeFactory.create(config);
            ourBridges.put(config.getName(), bridge);
            addServerTools(config, bridge, taken, theConfirmation, tools);
        }
        ourSnapshot = List.copyOf(current);
        ourTools = Collections.unmodifiableList(tools);
        return ourTools;
    }

    /**
     * @param theConfig server settings
     * @return tools of the server, empty when it does not answer - one silent
     *         server must not cost the chat its other tools, the settings
     *         page test button gives the loud error instead
     */
    private McpToolInfo[] discoverQuietly(final McpServerConfig theConfig) {
        try {
            return ourDiscovery.discover(theConfig);
        } catch (final Exception ex) {
            ColleagueLog.error("[colleague] mcp server " + theConfig.getName()
                + " is not answering: " + (ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage()));
            return new McpToolInfo[0];
        }
    }

    /**
     * @param theConfig server settings
     * @param theBridge open channel of the server
     * @param theTaken names already offered, a remote tool never shadows one
     * @param theConfirmation who to ask before a write-like remote call runs
     * @param theTools list the accepted adapters are added to
     */
    private void addServerTools(final McpServerConfig theConfig, final ToolBridge theBridge,
        final Set<String> theTaken, final McpToolAdapter.Confirmation theConfirmation,
        final List<IToolExecutor> theTools) {
        for (final McpToolInfo tool : discoverQuietly(theConfig)) {
            final String advertised = McpToolAdapter.NAME_PREFIX
                + McpToolAdapter.safe(theConfig.getName()) + "_"
                + McpToolAdapter.safe(tool.name());
            // a remote tool never shadows a built-in one or another adapter
            if (!theTaken.contains(tool.name()) && !theTaken.contains(advertised)) {
                theTaken.add(advertised);
                theTools.add(new McpToolAdapter(theConfig.getName(), theBridge, tool, theConfirmation));
            }
        }
    }

    /** Drops every open channel; the next rebuild opens them again. */
    public synchronized void close() {
        closeBridges();
        ourSnapshot = null;
        ourTools = List.of();
    }

    /** Closes the cached channels; callers must not reuse the catalog after. */
    private void closeBridges() {
        for (final ToolBridge bridge : ourBridges.values()) {
            if (bridge instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (final Exception ex) {
                    // a channel that will not die quietly is dropped anyway
                }
            }
        }
        ourBridges.clear();
    }

    /**
     * @param theLeft previously seen server list
     * @param theRight currently stored server list
     * @return whether both describe the same servers with the same settings
     */
    private static boolean sameServers(final List<McpServerConfig> theLeft,
        final List<McpServerConfig> theRight) {
        if (theLeft.size() != theRight.size()) {
            return false;
        }
        for (int i = 0; i < theLeft.size(); i++) {
            if (!theLeft.get(i).sameSettings(theRight.get(i))) {
                return false;
            }
        }
        return true;
    }
}
