package com.mad.edt.colleague.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one place the user-typed context window is understood: the text
 * from the settings form becomes a token count or a complaint, and the range
 * that is safe to ask a model for is stated once. Before this contract every
 * consumer guessed at its own window, so a gateway provider could never be
 * told how much context it actually has.
 */
class ProviderConfigUserWindowTest {

    @Test
    @DisplayName("an empty field means the default of the provider type")
    void emptyMeansTypeDefault() {
        Assertions.assertEquals(0, ProviderConfig.parseUserContextWindowTokens(null));
        Assertions.assertEquals(0, ProviderConfig.parseUserContextWindowTokens(""));
        Assertions.assertEquals(0, ProviderConfig.parseUserContextWindowTokens("   "));
        Assertions.assertEquals(0, ProviderConfig.parseUserContextWindowTokens("0"),
            "an explicit zero resets to the type default like an empty field");
    }

    @Test
    @DisplayName("a plain number inside the safe range is taken as tokens")
    void plainNumberInsideTheRangeIsAccepted() {
        Assertions.assertEquals(32_768, ProviderConfig.parseUserContextWindowTokens("32768"));
        Assertions.assertEquals(ProviderConfig.MIN_USER_CONTEXT_TOKENS,
            ProviderConfig.parseUserContextWindowTokens(
                String.valueOf(ProviderConfig.MIN_USER_CONTEXT_TOKENS)),
            "the floor itself is still a valid window");
        Assertions.assertEquals(ProviderConfig.MAX_USER_CONTEXT_TOKENS,
            ProviderConfig.parseUserContextWindowTokens(
                String.valueOf(ProviderConfig.MAX_USER_CONTEXT_TOKENS)),
            "the ceiling itself is still a valid window");
    }

    @Test
    @DisplayName("a window too small to hold a normal prompt is refused")
    void belowTheFloorIsRefused() {
        final IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
            () -> ProviderConfig.parseUserContextWindowTokens("500"));
        Assertions.assertFalse(ex.getMessage().isEmpty(),
            "the refusal must name the range so the form can show it");
    }

    @Test
    @DisplayName("a window no model is safely asked for is refused")
    void aboveTheCeilingIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> ProviderConfig.parseUserContextWindowTokens("200000"),
            "a typo of this size would inflate the model cache without bound");
    }

    @Test
    @DisplayName("text that is not a number is refused, not guessed at")
    void nonNumericIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> ProviderConfig.parseUserContextWindowTokens("большое"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> ProviderConfig.parseUserContextWindowTokens("32k"));
    }
}
