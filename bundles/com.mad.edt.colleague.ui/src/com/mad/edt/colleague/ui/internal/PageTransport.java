package com.mad.edt.colleague.ui.internal;

import com.google.gson.JsonObject;

/**
 * One channel that carries page events from Java to a rendered chat page. The
 * embedded browser implements it through its script channel; the debug web
 * bridge implements it through an HTTP long poll. A chat panel can hand its
 * events to more than one transport, which is what lets the same conversation
 * be watched in a browser tab while the workbench shows it.
 */
public interface PageTransport {

    /**
     * Queues one event for the page.
     *
     * @param theKind event name seen by the page
     * @param thePayload JSON payload of the event
     */
    void push(String theKind, JsonObject thePayload);

    /**
     * Asks the transport to hand over everything queued so far. An empty batch
     * means "flush what you have", not "deliver nothing".
     *
     * @param theBatch ordered {@code [kind, json]} events, possibly empty
     */
    void pushEvents(java.util.List<Object[]> theBatch);
}
