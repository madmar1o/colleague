package com.mad.edt.colleague.context.vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;
import com.mad.edt.colleague.http.HttpTransport;

/**
 * In-memory stand-in for the vector server: answers calls in the scripted
 * order while recording what each call carried. Any call beyond the script is
 * an assertion failure, which pins exact call sequences.
 */
final class ScriptedHttpTransport implements HttpTransport {

    private final Deque<HttpCallResult> script = new ArrayDeque<>();
    private final List<HttpCallSpec> calls = new ArrayList<>();

    /**
     * @param theStatus answer status
     * @param theBodyText buffered answer body
     * @return this transport with one more answer queued
     */
    ScriptedHttpTransport answer(final int theStatus, final String theBodyText) {
        script.add(HttpCallResult.ofBody(theStatus, theBodyText));
        return this;
    }

    @Override
    public HttpCallResult send(final HttpCallSpec theCall) {
        calls.add(theCall);
        final HttpCallResult next = script.poll();
        if(next == null) {
            throw new AssertionError("unexpected call beyond the script: "
                + theCall.getMethod() + ' ' + theCall.getUrl());
        }
        return next;
    }

    /**
     * @return every call seen, in order
     */
    List<HttpCallSpec> getCalls() {
        return calls;
    }

    /**
     * @param theIndex zero-based call number
     * @return that call
     */
    HttpCallSpec callAt(final int theIndex) {
        if(theIndex >= calls.size()) {
            throw new AssertionError("expected at least " + (theIndex + 1)
                + " calls, got " + calls.size());
        }
        return calls.get(theIndex);
    }

    /**
     * @return number of calls made
     */
    int getCallCount() {
        return calls.size();
    }
}
