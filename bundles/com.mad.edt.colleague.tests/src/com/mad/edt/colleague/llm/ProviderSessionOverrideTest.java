package com.mad.edt.colleague.llm;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model runtime is the user's choice, so the plug-in must not decide it:
 * a start-up flag can steer which registered provider one session talks to,
 * and that steering must stay inside the session. These cases pin the seam:
 * an unknown id is refused rather than invented, a known one is accepted, no
 * flag leaves the stored choice alone, and a session override never writes
 * itself back into the workspace settings.
 */
class ProviderSessionOverrideTest {

    @Test
    @DisplayName("a flag naming a registered provider picks it for this session")
    void knownProviderIsPicked() {
        Assertions.assertEquals("srata",
            ProviderStore.sessionOverrideId("srata", List.of("local", "srata")));
    }

    @Test
    @DisplayName("a flag naming nothing registered picks nothing")
    void unknownProviderIsRefused() {
        Assertions.assertEquals("",
            ProviderStore.sessionOverrideId("made-up", List.of("local", "srata")),
            "a typo in the flag must not invent an endpoint");
    }

    @Test
    @DisplayName("no flag, no override: the stored choice rules")
    void noFlagKeepsTheStoredChoice() {
        Assertions.assertEquals("", ProviderStore.sessionOverrideId(null, List.of("local")));
        Assertions.assertEquals("", ProviderStore.sessionOverrideId("  ", List.of("local")));
    }

    @Test
    @DisplayName("the override is compared by trimmed name, not by exact spacing")
    void spacesAroundTheFlagDoNotMatter() {
        Assertions.assertEquals("srata",
            ProviderStore.sessionOverrideId(" srata ", List.of("local", "srata")));
    }

    @Test
    @DisplayName("a session override never reaches the stored settings")
    void storedDefaultSurvivesTheOverride() {
        final ProviderStore.SessionDefaults defaults =
            new ProviderStore.SessionDefaults("Windows ollama", "srata");

        Assertions.assertEquals("srata", defaults.forThisSession());
        Assertions.assertEquals("Windows ollama", defaults.toStore(),
            "a debug run must not rewrite which provider the user chose");
    }

    @Test
    @DisplayName("without an override the session and the stored default agree")
    void noOverrideStoresWhatItRan() {
        final ProviderStore.SessionDefaults defaults =
            new ProviderStore.SessionDefaults("Windows ollama", "");

        Assertions.assertEquals("Windows ollama", defaults.forThisSession());
        Assertions.assertEquals("Windows ollama", defaults.toStore());
        Assertions.assertTrue(Set.of(defaults.forThisSession()).contains("Windows ollama"));
    }

    @Test
    @DisplayName("a provider picked in the settings page during a flagged session is kept")
    void aUserPickInsideAFlaggedSessionSurvives() {
        final ProviderStore.SessionDefaults defaults =
            new ProviderStore.SessionDefaults("Windows ollama", "srata");

        Assertions.assertEquals("another", defaults.toStoreFor("another"),
            "a pick made in the settings page is the user's decision and gets stored");
        Assertions.assertEquals("Windows ollama", defaults.toStoreFor("srata"),
            "the flag alone never rewrites the stored pick");
    }
}
