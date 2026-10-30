package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

/**
 * Persists provider configurations - the built-in local one included - in the
 * workspace instance preferences as a single JSON document. The built-in
 * provider always exists, but its address and model are user settings: a
 * stored entry for it overrides what the code seeds, so nothing about the
 * endpoint is fixed once the user has chosen it.
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
            final Map<String, ProviderConfig> stored =
                ProviderDocument.fromJson(GSON.fromJson(json, JsonArray.class));
            for(final ProviderConfig config : stored.values()) {
                // a stored entry wins over the built-in defaults: the user
                // may have moved the local runtime or picked another model
                LLMSettings.register(config);
            }
        } catch (final RuntimeException ex) {
            // corrupt preferences must not break startup; keep built-ins only
        }
    }

    /**
     * Rewrites the stored document from the currently registered providers,
     * the built-in local one included: its settings are the user's choice,
     * not a constant.
     */
    public static synchronized void saveFromSettings() {
        final List<ProviderConfig> registered = new ArrayList<>();
        for(final String id : LLMSettings.getProviderIds()) {
            final ProviderConfig config = LLMSettings.getConfig(id);
            if(config != null) {
                registered.add(config);
            }
        }
        final Preferences preferences = node();
        preferences.put(KEY_PROVIDERS, GSON.toJson(ProviderDocument.toJson(registered)));
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
}
