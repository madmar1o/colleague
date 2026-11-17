package com.mad.edt.colleague.mcp;

import java.util.ArrayList;
import java.util.List;

import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Stores the user's MCP server list in the workspace instance preferences as
 * one JSON document under node {@code com.mad.edt.colleague.mcp}, key
 * {@code servers.json}. The catalog re-reads this list on every agent run and
 * rebuilds itself only when the stored values actually changed.
 */
public final class McpSettings {

    private static final String NODE_ID = "com.mad.edt.colleague.mcp";
    private static final String KEY_SERVERS = "servers.json";
    /** Persistence field name marking a server entry as active. */
    private static final String ENABLED = "enabled";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private McpSettings() {
        // utility
    }

    /**
     * @return the stored servers in list order; a corrupt document reads as
     *         empty rather than breaking the chat
     */
    public static synchronized List<McpServerConfig> loadServers() {
        final String json = node().get(KEY_SERVERS, "");
        if (json.isEmpty()) {
            return List.of();
        }
        final List<McpServerConfig> servers = new ArrayList<>();
        try {
            final JsonArray stored = GSON.fromJson(json, JsonArray.class);
            for (final JsonElement element : stored) {
                final McpServerConfig config = fromJson(element.getAsJsonObject());
                if (config != null) {
                    servers.add(config);
                }
            }
        } catch (final RuntimeException ex) {
            return List.of();
        }
        return List.copyOf(servers);
    }

    /**
     * Rewrites the whole document and advances the reload counter.
     *
     * @param theServers servers to store, in list order
     */
    public static synchronized void saveServers(final List<McpServerConfig> theServers) {
        final JsonArray stored = new JsonArray();
        for (final McpServerConfig config : theServers) {
            stored.add(toJson(config));
        }
        final Preferences preferences = node();
        preferences.put(KEY_SERVERS, GSON.toJson(stored));
        try {
            preferences.flush();
        } catch (final BackingStoreException ex) {
            // preferences stay in memory; the next save retries the disk
        }
    }

    /**
     * Inserts or replaces one entry by name, keeping the rest.
     *
     * @param theConfig entry to store
     */
    public static synchronized void upsert(final McpServerConfig theConfig) {
        final List<McpServerConfig> servers = new ArrayList<>(loadServers());
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).getName().equals(theConfig.getName())) {
                servers.set(i, theConfig);
                saveServers(servers);
                return;
            }
        }
        servers.add(theConfig);
        saveServers(servers);
    }

    /**
     * @param theName server entry to drop
     */
    public static synchronized void remove(final String theName) {
        final List<McpServerConfig> servers = new ArrayList<>(loadServers());
        servers.removeIf(config -> config.getName().equals(theName));
        saveServers(servers);
    }

    private static Preferences node() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(NODE_ID);
    }

    private static JsonObject toJson(final McpServerConfig theConfig) {
        final JsonObject json = new JsonObject();
        json.addProperty("name", theConfig.getName());
        json.addProperty(ENABLED, Boolean.valueOf(theConfig.isEnabled()));
        if (theConfig.isHttp()) {
            json.addProperty("url", theConfig.getUrl());
        } else {
            final JsonArray command = new JsonArray();
            for (final String part : theConfig.getCommand()) {
                command.add(part);
            }
            json.add("command", command);
        }
        return json;
    }

    private static McpServerConfig fromJson(final JsonObject theJson) {
        final JsonElement name = theJson.get("name");
        if (name == null || name.getAsString().isEmpty()) {
            return null;
        }
        final JsonElement url = theJson.get("url");
        if (url != null && !url.isJsonNull() && !url.getAsString().isEmpty()) {
            return new McpServerConfig(name.getAsString(), null, url.getAsString(),
                theJson.has(ENABLED) && theJson.get(ENABLED).getAsBoolean());
        }
        final JsonElement command = theJson.get("command");
        if (command == null || !command.isJsonArray() || command.getAsJsonArray().isEmpty()) {
            return null;
        }
        final List<String> parts = new ArrayList<>();
        for (final JsonElement part : command.getAsJsonArray()) {
            parts.add(part.getAsString());
        }
        return new McpServerConfig(name.getAsString(), parts, null,
            theJson.has(ENABLED) && theJson.get(ENABLED).getAsBoolean());
    }
}
