package com.mad.edt.colleague.llm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;

/**
 * Pins the stored provider document: every registered provider - including
 * the built-in local one - is persisted, and on load its stored fields
 * override the built-in defaults. Before this contract the built-in provider
 * was skipped on save, so its address and model could never leave the code:
 * they were whatever the source file happened to contain.
 */
class ProviderDocumentTest {

    /** @return the built-in defaults as the code seeds them */
    private static ProviderConfig builtIn() {
        return ProviderConfig.builder(LLMSettings.LOCAL_PROVIDER_ID, LLMProviderType.OLLAMA)
            .baseUrl("http://built-in.test:11434")
            .build();
    }

    @Test
    @DisplayName("the built-in provider is stored like any other")
    void storesTheBuiltInProvider() {
        final JsonArray document =
            ProviderDocument.toJson(List.of(builtIn(), ProviderConfig
                .builder("cloud", LLMProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://gw.test")
                .apiKey("secret")
                .defaultModel("chat-large")
                .build()));

        Assertions.assertEquals(2, document.size(),
            "the built-in provider must be stored, its settings belong to the user");
    }

    @Test
    @DisplayName("a stored entry for the built-in provider overrides its defaults")
    void storedEntryOverridesTheBuiltInDefaults() {
        final JsonArray document = new JsonArray();
        final com.google.gson.JsonObject stored = new com.google.gson.JsonObject();
        stored.addProperty("id", LLMSettings.LOCAL_PROVIDER_ID);
        stored.addProperty("type", LLMProviderType.OLLAMA.name());
        stored.addProperty("baseUrl", "http://user-chosen.test:9999");
        stored.addProperty("defaultModel", "user-picked-model");
        document.add(stored);

        final Map<String, ProviderConfig> parsed = ProviderDocument.fromJson(document);

        final ProviderConfig local = parsed.get(LLMSettings.LOCAL_PROVIDER_ID);
        Assertions.assertNotNull(local);
        Assertions.assertEquals("http://user-chosen.test:9999", local.getBaseUrl(),
            "the address the user saved wins over anything in the code");
        Assertions.assertEquals("user-picked-model", local.getDefaultModel());
    }

    @Test
    @DisplayName("a half-filled or unknown entry is dropped, not guessed at")
    void dropsIncompleteAndUnknownEntries() {
        final JsonArray document = new JsonArray();
        final com.google.gson.JsonObject noUrl = new com.google.gson.JsonObject();
        noUrl.addProperty("id", "broken");
        noUrl.addProperty("type", LLMProviderType.OLLAMA.name());
        document.add(noUrl);
        final com.google.gson.JsonObject badType = new com.google.gson.JsonObject();
        badType.addProperty("id", "weird");
        badType.addProperty("type", "CRYSTAL_BALL");
        badType.addProperty("baseUrl", "http://x.test");
        document.add(badType);

        Assertions.assertTrue(ProviderDocument.fromJson(document).isEmpty(),
            "an entry the code cannot interpret must not produce a half-configured provider");
    }

    @Test
    @DisplayName("a missing or empty document parses to nothing instead of throwing")
    void anAbsentDocumentParsesToNothing() {
        Assertions.assertTrue(ProviderDocument.fromJson(null).isEmpty());
        Assertions.assertTrue(ProviderDocument.fromJson(new JsonArray()).isEmpty());
    }
}
