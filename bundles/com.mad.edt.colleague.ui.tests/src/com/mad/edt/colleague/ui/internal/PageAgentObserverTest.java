package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentObserver;

/**
 * Pins what the page learns about an agent run: every executed tool becomes a
 * chip with a short argument flavour, every model round-trip becomes a status
 * line, and the console trace keeps receiving the same events. Without this
 * the agent tab shows a typing animation for minutes and the user cannot tell
 * whether the model is thinking, searching or stuck.
 */
class PageAgentObserverTest {

    /** records the console-side trace calls */
    private static final class Trace implements AgentObserver {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void onRound(final int theStep, final int theRequestedToolCalls) {
            calls.add("round|" + theStep + "|" + theRequestedToolCalls);
        }

        @Override
        public void onToolCall(final int theStep, final String theName,
            final String theArgumentsJson, final String theResult, final long theElapsedMs) {
            calls.add("tool|" + theStep + "|" + theName);
        }
    }

    @Test
    void finishedToolCallBecomesAChipWithAShortArgumentFlavour() {
        final Trace trace = new Trace();
        final List<String> chips = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final PageAgentObserver observer = new PageAgentObserver(trace,
            chip -> chips.add(chip.toString()), statuses::add);

        observer.onToolCall(2, "search_code",
            "{\"query\":\"модуль объекта справочник проводка\"}", "1234 chars", 3977L);

        assertEquals(List.of("tool|2|search_code"), trace.calls);
        assertEquals(1, chips.size());
        final JsonObject chip = JsonParser.parseString(chips.get(0)).getAsJsonObject();
        assertEquals("search_code", chip.get("name").getAsString());
        assertEquals("done", chip.get("state").getAsString());
        assertTrue(chip.get("detail").getAsString().contains("модуль объекта"),
            "the chip must name what the tool searched for: " + chip);
    }

    @Test
    void toolCallCarryingAFilePathLetsTheChipOpenTheEditor() {
        final List<String> chips = new ArrayList<>();
        final PageAgentObserver observer = new PageAgentObserver(AgentObserver.NOOP,
            chip -> chips.add(chip.toString()), text -> { });

        observer.onToolCall(1, "read_file", "{\"file\":\"src/Module.bsl\"}", "ok", 3L);

        final JsonObject chip = JsonParser.parseString(chips.get(0)).getAsJsonObject();
        assertEquals("src/Module.bsl", chip.get("file").getAsString());
    }

    @Test
    void modelRoundBecomesAStatusLine() {
        final Trace trace = new Trace();
        final List<String> statuses = new ArrayList<>();
        final PageAgentObserver observer = new PageAgentObserver(trace,
            chip -> { }, statuses::add);

        observer.onRound(3, 2);
        observer.onRound(4, 0);

        assertEquals(List.of("round|3|2", "round|4|0"), trace.calls);
        assertEquals(2, statuses.size());
        assertTrue(statuses.get(0).contains("3"), statuses.get(0));
        assertTrue(statuses.get(0).contains("2"), "the line must say how many calls are coming: "
            + statuses.get(0));
        assertTrue(statuses.get(1).contains("4"), statuses.get(1));
    }

    @Test
    void malformedArgumentsNeverBreakTheObserver() {
        final List<String> chips = new ArrayList<>();
        final PageAgentObserver observer = new PageAgentObserver(AgentObserver.NOOP,
            chip -> chips.add(chip.toString()), text -> { });

        observer.onToolCall(1, "list_files", "not json at all", "error", 1L);

        final JsonObject chip = JsonParser.parseString(chips.get(0)).getAsJsonObject();
        assertEquals("list_files", chip.get("name").getAsString());
        assertEquals("", chip.get("detail").getAsString());
    }
}
