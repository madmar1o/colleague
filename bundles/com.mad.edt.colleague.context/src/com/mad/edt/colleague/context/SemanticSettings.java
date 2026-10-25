package com.mad.edt.colleague.context;

import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Session-wide configuration of the semantic (vector) context layer, kept in
 * the same style as the provider settings: volatile static values seeded from
 * workspace instance preferences once per session. Semantic search stays off
 * until a vector store URL is registered - by {@link #configure(String,String,String)}
 * or through the stored preferences - and the embedding endpoint falls back
 * to the local model runtime URL when not set separately.
 */
public final class SemanticSettings {

    /** Preference node holding the semantic layer configuration. */
    private static final String NODE_ID = "com.mad.edt.colleague.context";
    private static final String KEY_STORE_URL = "semantic.store-url";
    private static final String KEY_EMBED_URL = "semantic.embed-url";
    private static final String KEY_MODEL = "semantic.model";

    private static volatile String ourStoreUrl = "";
    private static volatile String ourEmbedUrl = "";
    private static volatile String ourModel = "";
    private static volatile boolean ourLoaded;

    private SemanticSettings() {
        // utility
    }

    /**
     * Reads the stored configuration into the static values once per session;
     * later in-session overrides are deliberately not wiped by repeat calls.
     */
    public static synchronized void loadOnce() {
        if(ourLoaded) {
            return;
        }
        ourLoaded = true;
        final Preferences preferences = node();
        final String store = preferences.get(KEY_STORE_URL, "");
        final String model = preferences.get(KEY_MODEL, "");
        if(!store.isEmpty() && !model.isEmpty()) {
            ourStoreUrl = store;
            ourModel = model;
            ourEmbedUrl = preferences.get(KEY_EMBED_URL, "");
        }
    }

    /**
     * Sets the configuration for this session and stores it in the workspace
     * instance preferences so the next session starts with it.
     *
     * @param theStoreUrl vector store root URL, empty string turns the
     *            semantic layer off
     * @param theEmbedUrl embedding endpoint root URL, empty for the default
     *            local model runtime
     * @param theModel embedding model name, empty turns the layer off
     */
    public static synchronized void configure(final String theStoreUrl,
        final String theEmbedUrl, final String theModel) {
        ourStoreUrl = theStoreUrl == null ? "" : theStoreUrl.trim();
        ourEmbedUrl = theEmbedUrl == null ? "" : theEmbedUrl.trim();
        ourModel = theModel == null ? "" : theModel.trim();
        final Preferences preferences = node();
        preferences.put(KEY_STORE_URL, ourStoreUrl);
        preferences.put(KEY_EMBED_URL, ourEmbedUrl);
        preferences.put(KEY_MODEL, ourModel);
        try {
            preferences.flush();
        } catch (final BackingStoreException ex) {
            // preferences remain in memory; the next save retries
        }
    }

    /** @return vector store root URL, empty when the layer is off */
    public static String getStoreUrl() {
        return ourStoreUrl;
    }

    /** @return embedding endpoint root URL, empty for the default runtime */
    public static String getEmbedUrl() {
        return ourEmbedUrl;
    }

    /** @return embedding model name, empty when the layer is off */
    public static String getModel() {
        return ourModel;
    }

    /** @return whether a store URL and a model name are both configured */
    public static boolean isConfigured() {
        return !ourStoreUrl.isEmpty() && !ourModel.isEmpty();
    }

    /**
     * @return the configured embedding endpoint or the fallback URL when the
     *         endpoint was not set separately
     */
    public static String resolveEmbedUrl() {
        return ourEmbedUrl.isEmpty() ? defaultEmbedUrl() : ourEmbedUrl;
    }

    private static String defaultEmbedUrl() {
        return com.mad.edt.colleague.llm.LLMSettings.LOCAL_RUNTIME_URL;
    }

    private static Preferences node() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(NODE_ID);
    }
}
