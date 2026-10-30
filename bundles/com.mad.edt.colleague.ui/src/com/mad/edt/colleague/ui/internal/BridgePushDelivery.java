package com.mad.edt.colleague.ui.internal;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.JsonArray;

/**
 * Carries a batch of bridge events from Java into the embedded page without
 * waiting for the page to ask. The page exposes {@code window.colleagueEvents}
 * for exactly this purpose; running script inside the page is only safe from
 * the UI thread, so every batch is first queued as a pending delivery and
 * executed on the next UI tick. Empty batches are dropped: scheduling a call
 * of an empty handler would buy nothing and could outrank real deliveries.
 */
final class BridgePushDelivery {

    /**
     * Wraps batch text into the page call that applies it. The angle brackets
     * are escaped because a payload may hold HTML or a script-closing tag and
     * the text is pasted straight into the script source.
     *
     * @param theBatch {@code [["kind","{json}"],...]} text as rendered by
     *            {@link #renderBatch(List)}
     * @return the script that hands the batch to {@code colleagueEvents}
     */
    static String pushScript(final String theBatch) {
        return "window.colleagueEvents(" + theBatch.replace("<", "\\u003c") + ")";
    }

    private final Function<String, Runnable> ourDeliverer;
    private final Consumer<Runnable> ourScheduler;
    /** rendered events waiting for the tick; merged, never replaced */
    private String ourPending = "[]";
    /** one tick is enough for everything queued so far */
    private boolean ourTickPending;

    /**
     * @param theDeliverer turns script text into the runnable that runs it in
     *            the page; the runnable is only invoked once the scheduler
     *            hands it back on a UI tick
     * @param theScheduler queues a runnable for the next UI tick
     */
    BridgePushDelivery(final Function<String, Runnable> theDeliverer,
        final Consumer<Runnable> theScheduler) {
        ourDeliverer = theDeliverer;
        ourScheduler = theScheduler;
    }

    /**
     * Renders the batch into a page call and queues it for the next UI tick.
     * The call is not run here: the scheduler decides when the page may be
     * entered from Java.
     *
     * @param theBatch ordered {@code [kind, json]} events to deliver
     * @return script text queued for delivery, empty text when nothing ran
     */
    String schedule(final List<Object[]> theBatch) {
        if(theBatch.isEmpty()) {
            return "";
        }
        return scheduleBatch(renderBatch(theBatch));
    }

    /**
     * Queues an already rendered batch, for callers that rendered it for
     * another purpose first (the navigation-channel polling reply). Events
     * queued before the next widget tick leave together, in arrival order.
     *
     * @param theBatch {@code [["kind","{json}"],...]} text as produced by
     *            {@link #renderBatch(List)}
     * @return script text queued for delivery, empty text when nothing ran
     */
    String scheduleBatch(final String theBatch) {
        if("[]".equals(theBatch)) {
            return "";
        }
        final String merged;
        synchronized(this) {
            ourPending = merge(ourPending, theBatch);
            merged = ourPending;
            if(!ourTickPending) {
                ourTickPending = true;
                ourScheduler.accept(this::deliverPending);
            }
        }
        return pushScript(merged);
    }

    /**
     * Hands everything queued before this tick to the page as one script.
     * A burst of events (question echo, busy flag, status line) therefore
     * reaches the page in the order Java produced it: separate ticks may run
     * in any order on the widget thread, while one script keeps its order.
     */
    private void deliverPending() {
        final String batch;
        synchronized(this) {
            batch = ourPending;
            ourPending = "[]";
            ourTickPending = false;
        }
        if("[]".equals(batch)) {
            return;
        }
        ourDeliverer.apply(pushScript(batch)).run();
    }

    /**
     * @param theFirst already queued events text, {@code []} for none
     * @param theSecond new events text
     * @return both event arrays as one array text
     */
    private static String merge(final String theFirst, final String theSecond) {
        if("[]".equals(theFirst)) {
            return theSecond;
        }
        return theFirst.substring(0, theFirst.length() - 1) + "," + theSecond.substring(1);
    }

    /**
     * Renders ordered events into the array text the page unpacks through its
     * {@code colleagueEvents} handler; shared with the synchronous reply path
     * so both channels carry byte-identical batches.
     *
     * @param theBatch ordered {@code [kind, json]} events to render
     * @return {@code [["kind","{json}"],...]} text, possibly {@code []}
     */
    static String renderBatch(final List<Object[]> theBatch) {
        final JsonArray events = new JsonArray();
        for(final Object[] entry : theBatch) {
            final JsonArray event = new JsonArray();
            event.add(String.valueOf(entry[0]));
            event.add(String.valueOf(entry[1]));
            events.add(event);
        }
        return events.toString();
    }
}
