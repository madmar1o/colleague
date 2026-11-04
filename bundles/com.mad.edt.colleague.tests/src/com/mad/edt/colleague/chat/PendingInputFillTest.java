package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the contract of {@link PendingInputFill}: a question staged for a chat
 * page that has not announced itself yet survives until the page is ready,
 * and is handed out exactly once.
 */
class PendingInputFillTest {

    @Test
    void stagedTextSurvivesUntilDrained() {
        final PendingInputFill fill = new PendingInputFill();
        fill.stage("explain this");
        assertEquals("explain this", fill.drain());
    }

    @Test
    void drainHandsOutOnlyOnce() {
        final PendingInputFill fill = new PendingInputFill();
        fill.stage("one");
        fill.drain();
        assertTrue(fill.drain().isEmpty());
    }

    @Test
    void laterStageReplacesEarlierOne() {
        final PendingInputFill fill = new PendingInputFill();
        fill.stage("first");
        fill.stage("second");
        assertEquals("second", fill.drain());
    }

    @Test
    void blankStageIsIgnored() {
        final PendingInputFill fill = new PendingInputFill();
        fill.stage("  ");
        assertTrue(fill.drain().isEmpty());
    }
}
