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

        call(observer, "read_file");
        call(observer, "read_file");
        stats.finishRun();
        stats.noteOffered(List.of("read_file", "search_code"));
        call(observer, "search_code");
        stats.finishRun();

        Assertions.assertEquals(2, stats.callsOf("read_file"));
        Assertions.assertEquals(1, stats.callsOf("search_code"));
        Assertions.assertEquals(0, stats.callsOf("never_called"));
    }

    @Test
    @DisplayName("a tool unused for N offered runs stops being advertised")
    void unusedToolsLeaveTheCatalog() {
        final ToolUsageStats stats = new ToolUsageStats();
        final ToolPolicy policy = new ToolPolicy().excludeUnused(2);
        final List<ToolSpec> offered = Arrays.asList(spec("read_file"), spec("plan_tool"));

        stats.noteOffered(List.of("read_file", "plan_tool"));
        call(new LoggingAgentObserver(stats), "read_file");
        stats.finishRun();
        Assertions.assertTrue(policy.shouldOffer(stats, "read_file"));
        Assertions.assertTrue(policy.shouldOffer(stats, "plan_tool"),
            "one run without a call is not yet a reason to prune");

        stats.noteOffered(List.of("read_file", "plan_tool"));
        call(new LoggingAgentObserver(stats), "read_file");
        stats.finishRun();
        stats.noteOffered(List.of("read_file", "plan_tool"));
        call(new LoggingAgentObserver(stats), "read_file");
        stats.finishRun();
        Assertions.assertFalse(policy.shouldOffer(stats, "plan_tool"),
            "three runs offered without a call must prune the tool");
        Assertions.assertTrue(policy.shouldOffer(stats, "read_file"),
            "a used tool never leaves the catalog");
        Assertions.assertEquals(List.of("read_file"),
            names(policy.allowed(null, offered, stats)));
        // a tool nobody ever offered is not penalised for never being used
        Assertions.assertTrue(policy.shouldOffer(stats, "brand_new"));
    }

    @Test
    @DisplayName("one call resets the idle streak of a pruned tool")
    void usedToolResetsTheStreak() {
        final ToolUsageStats stats = new ToolUsageStats();
        final ToolPolicy policy = new ToolPolicy().excludeUnused(1);

        stats.noteOffered(List.of("plan_tool"));
        stats.finishRun();
        Assertions.assertFalse(policy.shouldOffer(stats, "plan_tool"),
            "one unused run already prunes at threshold one");

        stats.noteOffered(List.of("plan_tool"));
        call(new LoggingAgentObserver(stats), "plan_tool");
        stats.finishRun();
        Assertions.assertTrue(policy.shouldOffer(stats, "plan_tool"),
            "the call during the run reset the streak");
    }
}
