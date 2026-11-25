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

    @Test
    @DisplayName("the context window the user chose survives the round trip")
    void contextWindowRoundTrips() {
        final JsonArray document = ProviderDocument.toJson(List.of(
            ProviderConfig.builder("gw", LLMProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://gw.test")
                .contextWindowTokens(32_768)
                .build(),
            ProviderConfig.builder("loc", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test")
                .contextWindowTokens(4_096)
                .build()));

        final Map<String, ProviderConfig> parsed = ProviderDocument.fromJson(document);

        Assertions.assertEquals(32_768, parsed.get("gw").getContextWindowTokens(),
            "a gateway window the user raised must not be lost on save");
        Assertions.assertEquals(4_096, parsed.get("loc").getContextWindowTokens(),
            "a local window the user shrank must not be lost on save");
    }

    @Test
    @DisplayName("an entry without the window field keeps the defaults of its type")
    void absentWindowFieldKeepsTypeDefaults() {
        final JsonArray document = new JsonArray();
        final com.google.gson.JsonObject gateway = new com.google.gson.JsonObject();
        gateway.addProperty("id", "gw");
        gateway.addProperty("type", LLMProviderType.OPENAI_COMPATIBLE.name());
        gateway.addProperty("baseUrl", "https://gw.test");
        document.add(gateway);
        final com.google.gson.JsonObject local = new com.google.gson.JsonObject();
        local.addProperty("id", LLMSettings.LOCAL_PROVIDER_ID);
        local.addProperty("type", LLMProviderType.OLLAMA.name());
        local.addProperty("baseUrl", "http://runtime.test");
        document.add(local);

        final Map<String, ProviderConfig> parsed = ProviderDocument.fromJson(document);

        Assertions.assertEquals(0, parsed.get("gw").getContextWindowTokens(),
            "a stored gateway entry from before the field existed stays opt-out");
        Assertions.assertEquals(ProviderConfig.DEFAULT_LOCAL_CONTEXT_TOKENS,
            parsed.get(LLMSettings.LOCAL_PROVIDER_ID).getContextWindowTokens(),
            "a stored local entry from before the field existed keeps the local default");
    }

    @Test
    @DisplayName("a window left at the type default is not written out")
    void typeDefaultsAreNotSpilledIntoTheDocument() {
        final JsonArray document = ProviderDocument.toJson(List.of(
            ProviderConfig.builder("gw", LLMProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://gw.test")
                .build(),
            builtIn()));

        Assertions.assertFalse(document.get(0).getAsJsonObject().has("contextWindowTokens"),
            "a gateway at its implicit opt-out stores no window");
        Assertions.assertFalse(document.get(1).getAsJsonObject().has("contextWindowTokens"),
            "a local provider at its default stores no window");
    }
}
