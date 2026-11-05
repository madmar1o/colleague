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

    /**
     * The provider and model the user actually talked to last, stored as one
     * {@code provider\u0000model} pair. Reported live on 2026-11-05: a new
     * session surfaced the build-time default model instead of the one the
     * user had been working with.
     */
    private static final String KEY_LAST_USED = "last-used";

    /**
     * The separator inside the last-used pair. It is the same one the chat
     * tab registry uses for its tab keys, so a stored pair reads the same
     * way a tab key does.
     */
    private static final char LAST_USED_SEPARATOR = '\u0000';

    /**
     * Start-up flag naming which registered provider this session talks to.
     * The plug-in never decides the model runtime itself: the flag is how a
     * person (or a verification run) says so without editing the stored
     * settings, and the choice is dropped when the session ends.
     */
    private static final String PROVIDER_OVERRIDE_PROPERTY = "colleague.provider";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean ourLoaded;

    /** What the workspace stored and what this session actually runs on. */
    private static SessionDefaults ourDefaults = new SessionDefaults("", "");

    /**
     * Which provider a start-up flag asks for, out of the ones the workspace
     * actually has.
     *
     * @param theFlagValue flag value, may be {@code null} or blank
     * @param theKnownIds provider ids the workspace has registered
     * @return the provider to use for this session, empty when the flag names
     *         nothing registered - a typo must not invent an endpoint
     */
    public static String sessionOverrideId(final String theFlagValue,
        final List<String> theKnownIds) {
        if(theFlagValue == null) {
            return "";
        }
        final String wanted = theFlagValue.trim();
        if(wanted.isEmpty()) {
            return "";
        }
        return theKnownIds != null && theKnownIds.contains(wanted) ? wanted : "";
    }

    /**
     * The two provider ids of one session: what the run talks to, and what may
     * be written back to the workspace settings.
     */
    public static final class SessionDefaults {

        private final String ourStored;
        private final String ourOverride;

        /**
         * @param theStored default provider the workspace has stored
         * @param theOverride provider this session was told to use, empty when
         *            no flag asked for one
         */
        public SessionDefaults(final String theStored, final String theOverride) {
            ourStored = theStored == null ? "" : theStored;
            ourOverride = theOverride == null ? "" : theOverride;
        }

        /** @return the provider this session talks to */
        public String forThisSession() {
            return ourOverride.isEmpty() ? ourStored : ourOverride;
        }

        /**
         * @return the provider to keep in the settings: a session override is
         *         not a user decision, so the stored choice survives it
         */
        public String toStore() {
            return ourOverride.isEmpty() ? forThisSession() : ourStored;
        }

        /**
         * Decides what the workspace settings should carry once the session
         * saves its providers.
         *
         * @param theCurrentDefault provider the session runs on right now,
         *            which the settings page may have moved on its own
         * @return the provider to store: the current one, except when the
         *         current one is only the session flag still in force
         */
        public String toStoreFor(final String theCurrentDefault) {
            final String current = theCurrentDefault == null ? "" : theCurrentDefault;
            if(!ourOverride.isEmpty() && ourOverride.equals(current)) {
                return ourStored;
            }
            return current;
        }
    }

    /**
     * Remembers the provider and model the user opened a chat with. Incomplete
     * input is not a user decision: a blank provider or a blank model leaves
     * the stored pair untouched, and a forged separator inside a value is
     * ignored the same way, so only complete pairs can ever be stored. The
     * write flushes immediately; a backing-store failure leaves the pair in
     * memory for the next save to retry.
     *
     * @param theProvider provider id of the opened chat
     * @param theModel model name of the opened chat
     */
    public static synchronized void rememberLastUsed(final String theProvider,
        final String theModel) {
        if(!isUsablePart(theProvider) || !isUsablePart(theModel)) {
            return;
        }
        final Preferences preferences = node();
        preferences.put(KEY_LAST_USED, theProvider + LAST_USED_SEPARATOR + theModel);
        try {
            preferences.flush();
        } catch (final BackingStoreException ex) {
            // preferences remain in memory; next save retries
        }
    }

    /** @return the provider of the last-used pair, empty when there is none */
    public static synchronized String lastUsedProvider() {
        return parseLastUsed()[0];
    }

    /** @return the model of the last-used pair, empty when there is none */
    public static synchronized String lastUsedModel() {
        return parseLastUsed()[1];
    }

    /**
     * Reads the stored pair once and splits it; a missing, malformed or
     * single-segment value reads as "no data", never as an exception — a
     * corrupted preferences entry must not break the startup path that
     * consults the pair.
     *
     * @return two elements, first the provider, second the model, empty
     *         strings when the stored value is not a usable pair
     */
    private static String[] parseLastUsed() {
        final String raw = node().get(KEY_LAST_USED, "");
        final int separator = raw.indexOf(LAST_USED_SEPARATOR);
        if(separator <= 0 || separator >= raw.length() - 1) {
            return new String[] {"", ""};
        }
        return new String[] {raw.substring(0, separator),
            raw.substring(separator + 1)};
    }

    /**
     * @param thePart candidate value of one half of a pair
     * @return whether the value is complete: non-null, not blank, and without
     *         the pair separator inside it
     */
    private static boolean isUsablePart(final String thePart) {
        return thePart != null && !thePart.isBlank()
            && thePart.indexOf(LAST_USED_SEPARATOR) < 0;
    }

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
        final String json = preferences.get(KEY_PROVIDERS, "");
        if(!json.isEmpty()) {
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
        // the flag is read after registration on purpose: it can only name a
        // provider the workspace really has
        final String override = sessionOverrideId(
            System.getProperty(PROVIDER_OVERRIDE_PROPERTY, ""), LLMSettings.getProviderIds());
        if(!override.isEmpty() && !override.equals(storedDefault)) {
            System.out.println("[colleague] provider this session: " + override
                + " (flag " + PROVIDER_OVERRIDE_PROPERTY + ", stored choice kept)");
        } else if(!System.getProperty(PROVIDER_OVERRIDE_PROPERTY, "").trim().isEmpty()) {
            System.out.println("[colleague] flag " + PROVIDER_OVERRIDE_PROPERTY + " names no"
                + " registered provider, keeping " + storedDefault);
        }
        ourDefaults = new SessionDefaults(storedDefault, override);
        if(!ourDefaults.forThisSession().isEmpty()) {
            LLMSettings.setDefaultProviderId(ourDefaults.forThisSession());
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
        // a session flag is not a user decision: whatever the run talked to,
        // the workspace keeps the provider its owner had picked
        preferences.put(KEY_DEFAULT,
            ourDefaults.toStoreFor(LLMSettings.getDefaultProviderId()));
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
