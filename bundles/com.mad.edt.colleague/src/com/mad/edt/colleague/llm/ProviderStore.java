package com.mad.edt.colleague.llm;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;import org.osgi.service.prefs.Preferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Persists user-added LLM provider configurations in the workspace instance
 * preferences as a single JSON document. The built-in local provider is not
 * stored; it always exists with its defaults.
 */
public final class ProviderStore {

    private static final String NODE_ID = "com.mad.edt.colleague.llm";
    private static final String KEY_PROVIDERS = "providers.json";
    private static final String KEY_DEFAULT = "default-provider";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean ourLoaded;

    private ProviderStore() {
        // utility
    }

    /**
     * Registers all stored providers into {@link LLMSettings} once per session.
     * Idempotent and cheap to call from view creation code.
     */
    public static synchronized void loadIntoSettings() {
        if(ourLoaded) {
            return;
        }
        ourLoaded = true;
        final Preferences preferences = node();
        final String storedDefault = preferences.get(KEY_DEFAULT, "");
        if(!storedDefault.isEmpty()) {
            LLMSettings.setDefaultProviderId(storedDefault);
        }
        final String json = preferences.get(KEY_PROVIDERS, "");
        if(json.isEmpty()) {
            return;
        }
        try {
            final JsonArray stored = GSON.fromJson(json, JsonArray.class);
            for(final JsonElement element : stored) {
                final ProviderConfig config = fromJson(element.getAsJsonObject());
                if(config != null && LLMSettings.getConfig(config.getId()) == null) {
                    LLMSettings.register(config);
                }
            }
        } catch (final RuntimeException ex) {
            // corrupt preferences must not break startup; keep built-ins only
        }
    }

    /**
     * Rewrites the stored document from the currently registered custom
     * providers (the built-in local one is skipped).
     */
    public static synchronized void saveFromSettings() {
        final JsonArray stored = new JsonArray();
        for(final String id : LLMSettings.getProviderIds()) {
            if(LLMSettings.LOCAL_PROVIDER_ID.equals(id)) {
                continue;
            }
            stored.add(toJson(LLMSettings.getConfig(id)));
        }
        final Preferences preferences = node();
        preferences.put(KEY_PROVIDERS, GSON.toJson(stored));
        preferences.put(KEY_DEFAULT, LLMSettings.getDefaultProviderId());
        try {
            preferences.flush();
        } catch (final BackingStoreException ex) {
            // preferences remain in memory; next save retries
        }
    }

    private static Preferences node() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(NODE_ID);
    }

    private static JsonObject toJson(final ProviderConfig theConfig) {
        final JsonObject json = new JsonObject();
        json.addProperty("id", theConfig.getId());
        json.addProperty("type", theConfig.getType().name());
        json.addProperty("baseUrl", theConfig.getBaseUrl());
        if(theConfig.getApiKey() != null && !theConfig.getApiKey().isEmpty()) {
            json.addProperty("apiKey", theConfig.getApiKey());
        }
        if(theConfig.getDefaultModel() != null) {
            json.addProperty("defaultModel", theConfig.getDefaultModel());
        }
        return json;
    }

    private static ProviderConfig fromJson(final JsonObject theJson) {
        final String id = optString(theJson, "id");
        final String type = optString(theJson, "type");
        final String baseUrl = optString(theJson, "baseUrl");
        if(id == null || type == null || baseUrl == null) {
            return null;
        }
        try {
            return ProviderConfig.builder(id, LLMProviderType.valueOf(type))
                .baseUrl(baseUrl)
                .apiKey(optString(theJson, "apiKey"))
                .defaultModel(optString(theJson, "defaultModel"))
                .build();
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private static String optString(final JsonObject theJson, final String theName) {
        final JsonElement element = theJson.get(theName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
