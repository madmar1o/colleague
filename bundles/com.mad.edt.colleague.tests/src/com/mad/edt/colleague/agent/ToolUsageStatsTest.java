package com.mad.edt.colleague.agent;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.agents.ToolPolicy;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * A tool the model never calls still costs prompt tokens in every round. The
 * observer counts calls by name across runs; once a tool has been offered for
 * N finished runs without a single call, the policy stops offering it, while
 * any call resets the streak and brings the tool back.
 */
class ToolUsageStatsTest {

    // fixture tool names repeated across the scenarios
    private static final String READ_FILE = "read_file";
    private static final String SEARCH_CODE = "search_code";
    private static final String PLAN_TOOL = "plan_tool";

    private static ToolSpec spec(final String theName) {
        return new ToolSpec(theName, "tool " + theName, new JsonObject());
    }

    private static List<String> names(final List<ToolSpec> theSpecs) {
        return theSpecs.stream().map(ToolSpec::getName).toList();
    }

    private static void call(final AgentObserver theObserver, final String theName) {
        theObserver.onToolCall(1, theName, "{}", "ok", 1L);
    }

    @Test
    @DisplayName("the observer counts tool calls per name across runs")
    void observerCountsCallsByName() {
        final ToolUsageStats stats = new ToolUsageStats();
        final AgentObserver observer = new LoggingAgentObserver(stats);

        call(observer, READ_FILE);
        call(observer, READ_FILE);
        stats.finishRun();
        stats.noteOffered(List.of(READ_FILE, SEARCH_CODE));
        call(observer, SEARCH_CODE);
        stats.finishRun();

        Assertions.assertEquals(2, stats.callsOf(READ_FILE));
        Assertions.assertEquals(1, stats.callsOf(SEARCH_CODE));
        Assertions.assertEquals(0, stats.callsOf("never_called"));
    }

    @Test
    @DisplayName("a tool unused for N offered runs stops being advertised")
    void unusedToolsLeaveTheCatalog() {
        final ToolUsageStats stats = new ToolUsageStats();
        final ToolPolicy policy = new ToolPolicy().excludeUnused(2);
        final List<ToolSpec> offered = Arrays.asList(spec(READ_FILE), spec(PLAN_TOOL));

        stats.noteOffered(List.of(READ_FILE, PLAN_TOOL));
        call(new LoggingAgentObserver(stats), READ_FILE);
        stats.finishRun();
        Assertions.assertTrue(policy.shouldOffer(stats, READ_FILE));
        Assertions.assertTrue(policy.shouldOffer(stats, PLAN_TOOL),
            "one run without a call is not yet a reason to prune");

        stats.noteOffered(List.of(READ_FILE, PLAN_TOOL));
        call(new LoggingAgentObserver(stats), READ_FILE);
        stats.finishRun();
        stats.noteOffered(List.of(READ_FILE, PLAN_TOOL));
        call(new LoggingAgentObserver(stats), READ_FILE);
        stats.finishRun();
        Assertions.assertFalse(policy.shouldOffer(stats, PLAN_TOOL),
            "three runs offered without a call must prune the tool");
        Assertions.assertTrue(policy.shouldOffer(stats, READ_FILE),
            "a used tool never leaves the catalog");
        Assertions.assertEquals(List.of(READ_FILE),
            names(policy.allowed(null, offered, stats)));
        // a tool nobody ever offered is not penalised for never being used
        Assertions.assertTrue(policy.shouldOffer(stats, "brand_new"));
    }

    @Test
    @DisplayName("one call resets the idle streak of a pruned tool")
    void usedToolResetsTheStreak() {
        final ToolUsageStats stats = new ToolUsageStats();
        final ToolPolicy policy = new ToolPolicy().excludeUnused(1);

        stats.noteOffered(List.of(PLAN_TOOL));
        stats.finishRun();
        Assertions.assertFalse(policy.shouldOffer(stats, PLAN_TOOL),
            "one unused run already prunes at threshold one");

        stats.noteOffered(List.of(PLAN_TOOL));
        call(new LoggingAgentObserver(stats), PLAN_TOOL);
        stats.finishRun();
        Assertions.assertTrue(policy.shouldOffer(stats, PLAN_TOOL),
            "the call during the run reset the streak");
    }
}
