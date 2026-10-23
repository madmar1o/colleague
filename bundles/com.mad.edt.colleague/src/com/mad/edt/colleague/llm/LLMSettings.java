package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in endpoint defaults and the current (volatile, session-scoped)
 * provider registry. M4 will persist user settings through Eclipse
 * Preferences and supply keys from secure storage; until then callers may
 * register configurations programmatically.
 */
public final class LLMSettings {

    /** Root URL of the local model runtime as exposed on this machine. */
    public static final String LOCAL_RUNTIME_URL = "http://127.0.0.1:11435";

    /** Identifier of the built-in local runtime provider. */
    public static final String LOCAL_PROVIDER_ID = "local";

    private static final Map<String, ProviderConfig> CONFIGS = createDefaultConfigs();

    private LLMSettings() {
        // utility
    }

    /**
     * @param theId provider identifier
     * @return configuration or {@code null} when unknown
     */
    public static synchronized ProviderConfig getConfig(final String theId) {
        return CONFIGS.get(theId);
    }

    /**
     * @return identifiers of all registered providers
     */
    public static synchronized List<String> getProviderIds() {
        return Collections.unmodifiableList(new ArrayList<>(CONFIGS.keySet()));
    }

    /**
     * Registers (or replaces) a provider configuration.
     *
     * @param theConfig the configuration to register
     */
    public static synchronized void register(final ProviderConfig theConfig) {
        CONFIGS.put(theConfig.getId(), theConfig);
    }

    /**
     * Removes a provider configuration.
     *
     * @param theId provider identifier
     */
    public static synchronized void unregister(final String theId) {
        CONFIGS.remove(theId);
    }

    private static Map<String, ProviderConfig> createDefaultConfigs() {
        final Map<String, ProviderConfig> configs = new LinkedHashMap<>();
        configs.put(LOCAL_PROVIDER_ID, ProviderConfig
            .builder(LOCAL_PROVIDER_ID, LLMProviderType.OLLAMA)
            .baseUrl(LOCAL_RUNTIME_URL)
            .defaultModel("qwen3:8b")
            .build());
        return configs;
    }
}
