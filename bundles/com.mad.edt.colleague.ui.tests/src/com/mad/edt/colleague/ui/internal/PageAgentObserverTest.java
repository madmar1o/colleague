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
 * chip that carries the full trace of its call - the arguments it was given,
 * a preview of the result it left behind and how long it took - and every
 * model round-trip that asks for tools opens a trace block the chips of that
 * round nest into. Without this the agent tab shows a typing animation for
 * minutes and the user cannot tell what the agent actually did.
 */
class PageAgentObserverTest {

    private static final String SEARCH_CODE = "search_code";

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

    /** records chips, status lines and trace-block openings in delivery order */
    private static final class Page {

        private final List<String> chips = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final List<String> traces = new ArrayList<>();

        PageAgentObserver attachTo(final Trace theTrace) {
            return new PageAgentObserver(theTrace,
                chip -> chips.add(chip.toString()), statuses::add,
                block -> traces.add(block.toString()));
        }

        JsonObject chip(final int theIndex) {
            return JsonParser.parseString(chips.get(theIndex)).getAsJsonObject();
        }

        JsonObject trace(final int theIndex) {
            return JsonParser.parseString(traces.get(theIndex)).getAsJsonObject();
        }
    }

    @Test
    void finishedToolCallCarriesTheWholeCallOnItsChip() {
        final Trace trace = new Trace();
        final Page page = new Page();
        final PageAgentObserver observer = page.attachTo(trace);

        observer.onToolCall(2, SEARCH_CODE,
            "{\"query\":\"модуль объекта справочник проводка\"}", "1234 chars", 3977L);

        assertEquals(List.of("tool|2|search_code"), trace.calls);
        assertEquals(1, page.chips.size());
        final JsonObject chip = page.chip(0);
        assertEquals(SEARCH_CODE, chip.get("name").getAsString());
        assertEquals("done", chip.get("state").getAsString());
        assertTrue(chip.get("detail").getAsString().contains("модуль объекта"),
            "the chip must name what the tool searched for: " + chip);
        // the expandable details: the page shows them under the chip on click
        assertEquals("{\"query\":\"модуль объекта справочник проводка\"}",
            chip.get("args").getAsString(), "the chip carries the full argument JSON");
        assertEquals("1234 chars", chip.get("result").getAsString());
        assertEquals(3977L, chip.get("elapsed").getAsLong());
    }

    @Test
    void longResultsArriveOnTheChipAsAClippedPreview() {
        final Page page = new Page();
        final PageAgentObserver observer = page.attachTo(new Trace());
        final StringBuilder longResult = new StringBuilder();
        while(longResult.length() < 900) {
            longResult.append("line of a search result\n");
        }

        observer.onToolCall(1, SEARCH_CODE, "{}", longResult.toString(), 10L);

        final String preview = page.chip(0).get("result").getAsString();
        assertTrue(preview.length() <= 401,
            "a full file dump must not travel to the page: " + preview.length());
        assertTrue(longResult.toString().startsWith(preview.substring(0, 100)),
            "the preview opens with the real head of the result");
        assertTrue(preview.endsWith("…"), "the cut is marked: " + preview);
    }

    @Test
    void aToolAskingRoundOpensATraceBlockAndTheFinalRoundStaysAStatusLine() {
        final Trace trace = new Trace();
        final Page page = new Page();
        final PageAgentObserver observer = page.attachTo(trace);

        observer.onRound(3, 2);
        observer.onRound(4, 0);

        assertEquals(List.of("round|3|2", "round|4|0"), trace.calls);
        // the round that asks for tools opens the block its chips nest into;
        // the bridge queue puts this event ahead of those chips
        assertEquals(1, page.traces.size());
        final JsonObject block = page.trace(0);
        assertEquals(3, block.get("round").getAsInt());
        assertEquals(2, block.get("calls").getAsInt());
        // the answer round has no chips to group, it stays a plain status
        assertEquals(1, page.statuses.size());
        assertTrue(page.statuses.get(0).contains("4"), page.statuses.get(0));
    }

    @Test
    void toolCallCarryingAFilePathLetsTheChipOpenTheEditor() {
        final Page page = new Page();
        final PageAgentObserver observer = page.attachTo(new Trace());

        observer.onToolCall(1, "read_file", "{\"file\":\"src/Module.bsl\"}", "ok", 3L);

        assertEquals("src/Module.bsl", page.chip(0).get("file").getAsString());
    }

    @Test
    void malformedArgumentsNeverBreakTheObserver() {
        final Page page = new Page();
        final PageAgentObserver observer = page.attachTo(new Trace());

        observer.onToolCall(1, "list_files", "not json at all", "error", 1L);

        final JsonObject chip = page.chip(0);
        assertEquals("list_files", chip.get("name").getAsString());
        assertEquals("", chip.get("detail").getAsString());
        assertEquals("not json at all", chip.get("args").getAsString(),
            "even unreadable arguments travel verbatim: the user sees what the model sent");
    }
}
