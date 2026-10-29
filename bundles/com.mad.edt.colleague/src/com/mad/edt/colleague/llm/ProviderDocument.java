package com.mad.edt.colleague.llm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The stored form of the provider list: one JSON document holding every
 * registered provider, the built-in local one included. Its address and
 * default model are user settings like any other provider's, so the document
 * round-trips them; the built-in entry only supplies what the stored one left
 * out. Pure parsing and rendering, no preferences access, so the contract is
 * testable without a workspace.
 */
public final class ProviderDocument {

    private ProviderDocument() {
        // utility
    }

    /**
     * Renders the given providers into the stored array.
     *
     * @param theConfigs providers to store, in registration order
     * @return the JSON document to persist
     */
    public static JsonArray toJson(final Collection<ProviderConfig> theConfigs) {
        final JsonArray stored = new JsonArray();
        for(final ProviderConfig config : theConfigs) {
            stored.add(entry(config));
        }
        return stored;
    }

    /**
     * Parses the stored array into provider configurations. An entry the code
     * cannot interpret - missing fields or an unknown endpoint dialect - is
     * dropped rather than half-applied.
     *
     * @param theDocument stored array, {@code null} when nothing was saved
     * @return configurations keyed by provider id, in stored order
     */
    public static Map<String, ProviderConfig> fromJson(final JsonArray theDocument) {
        final Map<String, ProviderConfig> parsed = new LinkedHashMap<>();
        if(theDocument == null) {
            return parsed;
        }
        for(final JsonElement element : theDocument) {
            if(!element.isJsonObject()) {
                continue;
            }
            final ProviderConfig config = readEntry(element.getAsJsonObject());
            if(config != null) {
                parsed.put(config.getId(), config);
            }
        }
        return parsed;
    }

    private static JsonObject entry(final ProviderConfig theConfig) {
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

    private static ProviderConfig readEntry(final JsonObject theJson) {
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
