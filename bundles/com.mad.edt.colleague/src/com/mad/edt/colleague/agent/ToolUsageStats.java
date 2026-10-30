package com.mad.edt.colleague.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts which tools the model actually calls across agent runs. Every
 * advertised tool costs prompt tokens in every round-trip whether the model
 * uses it or not, so the chat needs to know which names have gone quiet: the
 * observer reports each call here, the chat reports which names it offered
 * and where a run ends, and the policy asks this counter how many finished
 * runs a tool has sat out without a single call.
 */
public final class ToolUsageStats {

    /** Calls recorded per tool name since the counter came into being. */
    private final Map<String, Integer> calls = new LinkedHashMap<>();
    /** Runs each offered name has finished without being called. */
    private final Map<String, Integer> idleRuns = new LinkedHashMap<>();
    /** Names offered during the run currently in flight. */
    private final Map<String, Boolean> offeredThisRun = new LinkedHashMap<>();
    /** Names called during the run currently in flight. */
    private final Map<String, Boolean> usedThisRun = new LinkedHashMap<>();

    /**
     * Records that the model called one tool. Also credits the tool for the
     * run in flight, which resets its idle streak when the run finishes.
     *
     * @param theName tool name as advertised
     */
    public synchronized void noteCall(final String theName) {
        if(theName == null || theName.isEmpty()) {
            return;
        }
        calls.merge(theName, Integer.valueOf(1), Integer::sum);
        usedThisRun.put(theName, Boolean.TRUE);
    }

    /**
     * Reports the names the chat is about to offer in the run in flight. Only
     * offered names can ever be penalised for going quiet, and a name that
     * stops being offered keeps its last idle count instead of decaying.
     *
     * @param theNames tool names offered for this run, in advertisement order
     */
    public synchronized void noteOffered(final List<String> theNames) {
        offeredThisRun.clear();
        for(final String name : theNames) {
            if(name != null && !name.isEmpty()) {
                offeredThisRun.put(name, Boolean.TRUE);
                calls.putIfAbsent(name, Integer.valueOf(0));
            }
        }
    }

    /**
     * Ends the run in flight: names that were offered but never called age
     * their idle streak by one, names that were called start a fresh streak.
     */
    public synchronized void finishRun() {
        for(final String name : offeredThisRun.keySet()) {
            if(usedThisRun.containsKey(name)) {
                idleRuns.put(name, Integer.valueOf(0));
            } else {
                idleRuns.merge(name, Integer.valueOf(1), Integer::sum);
            }
        }
        offeredThisRun.clear();
        usedThisRun.clear();
    }

    /**
     * @param theName tool name to look up
     * @return calls of this tool since the counter came into being, {@code 0}
     *         when it was never called
     */
    public synchronized int callsOf(final String theName) {
        return calls.getOrDefault(theName, Integer.valueOf(0)).intValue();
    }

    /**
     * @param theName tool name to look up
     * @return finished runs this tool was offered for but not called in,
     *         {@code 0} for a used or never-offered tool
     */
    public synchronized int idleRunsOf(final String theName) {
        return idleRuns.getOrDefault(theName, Integer.valueOf(0)).intValue();
    }

    /** @return call totals per name as an unmodifiable snapshot */
    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(calls));
    }
}
