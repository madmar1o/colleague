package com.mad.edt.colleague.chat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prompt budget of an agent turn. A local reasoning model reads the whole
 * prompt before it emits the first token, and a reasoning pass that starts
 * from a huge preloaded context spends its generation budget describing the
 * context instead of acting on the task. The trim keeps both ends of the
 * context - what matched first and the configuration picture that sits last
 * - and cuts only the middle, which the agent can still fetch through its
 * own search tools.
 */
class ContextBudgetTest {

    private static String filler(final int theChars) {
        final StringBuilder text = new StringBuilder();
        int block = 0;
        while(text.length() < theChars) {
            text.append("block").append(block++).append(" ").append("x".repeat(20)).append('\n');
        }
        return text.toString();
    }

    @Test
    @DisplayName("a context inside the budget passes through untouched")
    void insideBudgetUntouched() {
        final String text = "short context\nsecond line";
        Assertions.assertEquals(text, ContextBudget.trimMiddle(text, 200));
    }

    @Test
    @DisplayName("an oversized context keeps its first and last lines")
    void oversizedKeepsBothEnds() {
        final String text = "FIRST LINE marker\n" + filler(9_000) + "LAST LINE marker";
        final String trimmed = ContextBudget.trimMiddle(text, 3_000);

        Assertions.assertTrue(trimmed.length() <= 3_000, "length was " + trimmed.length());
        Assertions.assertTrue(trimmed.startsWith("FIRST LINE marker"), trimmed.substring(0, 40));
        Assertions.assertTrue(trimmed.endsWith("LAST LINE marker"),
            trimmed.substring(trimmed.length() - 40));
    }

    @Test
    @DisplayName("the cut is announced so the model knows content is missing")
    void cutIsAnnounced() {
        final String text = "head\n" + filler(9_000) + "\ntail";
        final String trimmed = ContextBudget.trimMiddle(text, 1_000);

        final int markerStart = trimmed.indexOf("[context trimmed: ");
        Assertions.assertTrue(markerStart >= 0, trimmed);
        final String marker = trimmed.substring(markerStart, trimmed.indexOf(']', markerStart) + 1);
        final int omitted = Integer.parseInt(marker.substring("[context trimmed: ".length(),
            marker.length() - " chars omitted]".length()));
        Assertions.assertEquals(text.length() - (trimmed.length() - marker.length()), omitted,
            "the marker must count exactly what the kept ends do not carry");
    }

    @Test
    @DisplayName("null context means no context")
    void nullIsEmpty() {
        Assertions.assertEquals("", ContextBudget.trimMiddle(null, 500));
    }
}
