package com.mad.edt.colleague.ui.internal;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Carries a batch of bridge events from Java into the embedded page without
 * waiting for the page to ask. The page exposes {@code window.colleagueEvents}
 * for exactly this purpose; running script inside the page is only safe from
 * the UI thread, so every batch is first queued as a pending delivery and
 * executed on the next UI tick. Empty batches are dropped: scheduling a call
 * of an empty handler would buy nothing and could outrank real deliveries.
 */
final class BridgePushDelivery {

    private final Function<String, Runnable> ourDeliverer;
    private final Consumer<Runnable> ourScheduler;

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
     * Skeleton placeholder - implemented in the next step.
     *
     * @param theBatch ordered {@code [kind, json]} events to deliver
     * @return script text queued for delivery, empty text when nothing ran
     */
    String schedule(final List<Object[]> theBatch) {
        return "";
    }

    /**
     * Skeleton placeholder - implemented in the next step.
     *
     * @param theBatch ordered {@code [kind, json]} events to render
     * @return {@code [["kind","{json}"],...]} text, possibly {@code []}
     */
    static String renderBatch(final List<Object[]> theBatch) {
        return "[]";
    }
}
