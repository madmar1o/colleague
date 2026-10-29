package com.mad.edt.colleague.agent.subagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mad.edt.colleague.llm.ChatMessage;

/**
 * Keeps the delegates of a chat session alive so the same agent name can be
 * asked again and continues where it stopped instead of starting from zero.
 * Only the most recent delegates are kept: the shelf is bounded, and the
 * oldest one leaves when a new one does not fit.
 */
public final class SubagentRegistry {

    /** Delegates kept alive at once; the oldest gives way beyond that. */
    public static final int MAX_SESSIONS = 8;

    /**
     * One live delegate: what it was asked for, which model served it, and the
     * conversation it can be continued from.
     *
     * @param description what the delegate was told to do, for the page card
     * @param modelName model serving this delegate, {@code null} for the
     *            parent's model
     * @param history conversation of the delegate, mutable so a later turn
     *            appends to it
     */
    public record Session(String description, String modelName, List<ChatMessage> history) {

        /**
         * @param theDescription what the delegate was told to do
         * @param theModelName model serving it, {@code null} for the parent's
         */
        public Session(final String theDescription, final String theModelName) {
            this(theDescription, theModelName, new ArrayList<>());
        }

        /**
         * @param theDescription what the delegate was told to do
         * @param theModelName model serving it
         * @param theHistory conversation to keep, copied out
         * @return session carrying that conversation
         */
        public static Session of(final String theDescription, final String theModelName,
            final List<ChatMessage> theHistory) {
            return new Session(theDescription, theModelName,
                new ArrayList<>(theHistory == null ? List.<ChatMessage>of() : theHistory));
        }

        /** @return conversation as an unmodifiable view */
        public List<ChatMessage> conversation() {
            return Collections.unmodifiableList(history);
        }
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();

    /**
     * Remembers a delegate under its name, replacing an older one with the
     * same name and evicting the oldest entry when the shelf overflows.
     *
     * @param theName delegate name, empty names are not kept
     * @param theSession live session of that delegate
     */
    public synchronized void put(final String theName, final Session theSession) {
        if(theName == null || theName.isEmpty() || theSession == null) {
            return;
        }
        sessions.remove(theName);
        sessions.put(theName, theSession);
        while(sessions.size() > MAX_SESSIONS) {
            sessions.remove(sessions.keySet().iterator().next());
        }
    }

    /**
     * @param theName delegate name
     * @return the live session, {@code null} when that delegate is not kept
     */
    public synchronized Session get(final String theName) {
        return sessions.get(theName);
    }

    /** @return number of delegates kept alive */
    public synchronized int size() {
        return sessions.size();
    }

    /** Drops every live delegate, the next call starts each one fresh. */
    public synchronized void clear() {
        sessions.clear();
    }
}
