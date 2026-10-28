package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the push path of the bridge: an event batch must reach the page through
 * a scripted call of {@code window.colleagueEvents} that Java schedules on its
 * own, without the page ever having to run a {@code sync} poll. The fake
 * receiver below stands in for the browser: it records the script text the
 * bridge queues and only runs it when the test replays the UI tick.
 */
class BridgePushDeliveryTest {

    /** fake browser script channel: records what was queued, runs on replay */
    private static final class FakePage {

        private final List<String> executed = new ArrayList<>();
        private final List<Runnable> scheduled = new ArrayList<>();

        private BridgePushDelivery create() {
            return new BridgePushDelivery(script -> () -> executed.add(script),
                scheduled::add);
        }

        /** replays the pending UI ticks, as the display would */
        void runPending() {
            final List<Runnable> pending = new ArrayList<>(scheduled);
            scheduled.clear();
            pending.forEach(Runnable::run);
        }

        int scriptCount() {
            return executed.size();
        }

        String lastScript() {
            return executed.get(executed.size() - 1);
        }
    }

    @Test
    void pushSchedulesTheBatchScriptWithoutAnyPageCall() {
        final FakePage page = new FakePage();
        final BridgePushDelivery delivery = page.create();

        delivery.schedule(Arrays.<Object[]>asList(new Object[] {"answer", "{\"text\":\"hi\"}"}));

        // the batch sits in the delivery queue before anything runs, and the
        // page was never asked for it: no sync, no function call
        assertEquals(0, page.scriptCount());

        page.runPending();
        assertEquals(1, page.scriptCount());
        assertEquals("window.colleagueEvents([[\"answer\",\"{\\\"text\\\":\\\"hi\\\"}\"]])",
            page.lastScript());
    }

    @Test
    void pushCarriesEveryKindsOrderAndPayloadsOfOneBatch() {
        final FakePage page = new FakePage();
        final BridgePushDelivery delivery = page.create();

        delivery.schedule(Arrays.<Object[]>asList(
            new Object[] {"delta", "{\"text\":\"a\"}"},
            new Object[] {"answer", "{\"text\":\"b\"}"}));
        page.runPending();

        assertEquals(1, page.scriptCount());
        assertEquals("window.colleagueEvents("
            + "[[\"delta\",\"{\\\"text\\\":\\\"a\\\"}\"],"
            + "[\"answer\",\"{\\\"text\\\":\\\"b\\\"}\"]])",
            page.lastScript());
    }

    @Test
    void emptyBatchIsNotScheduledAtAll() {
        final FakePage page = new FakePage();
        final BridgePushDelivery delivery = page.create();

        delivery.schedule(new ArrayList<>());
        page.runPending();

        assertEquals(0, page.scriptCount());
    }

    @Test
    void scriptTextIsSafeWhenAPayloadHoldsAnHtmlTag() {
        final FakePage page = new FakePage();
        final BridgePushDelivery delivery = page.create();

        delivery.schedule(Arrays.<Object[]>asList(
            new Object[] {"delta", "{\"text\":\"<b>bold</b>\"}"}));
        page.runPending();

        assertEquals(1, page.scriptCount());
        // no bare angle bracket may reach the script source: it would let a
        // payload open a tag or break out of the surrounding script text.
        // The escape is spelled at runtime because a literal backslash-u
        // sequence written in this source file is resolved by the compiler.
        String openEscape = "\\" + "u003c";
        assertTrue(page.lastScript().contains(openEscape + "b"));
        assertFalse(page.lastScript().contains("<"));
    }
}
