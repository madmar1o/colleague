package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pins the batching rules the chat page relies on: a chunk either rides the
 * accumulated batch or triggers one, the trigger being the size cap or the
 * time since the previous flush; nothing is ever sent twice or lost.
 */
class StreamBatcherTest {

    /** clock the test walks manually, one reading per accept() call */
    private static final class FakeClock {
        private long myNow;

        long now() {
            return myNow;
        }

        void advance(final long theMs) {
            myNow += theMs;
        }
    }

    private static StreamBatcher batcher(final FakeClock theClock) {
        return new StreamBatcher(200, 150, theClock::now);
    }

    @Test
    void firstChunkAlwaysGoesOutAtOnce() {
        final FakeClock clock = new FakeClock();
        clock.advance(10_000);
        final StreamBatcher batcher = batcher(clock);
        assertEquals("ok", batcher.accept("ok"));
    }

    @Test
    void chunksInsideTheIntervalRideTheNextFlush() {
        final FakeClock clock = new FakeClock();
        clock.advance(10_000);
        final StreamBatcher batcher = batcher(clock);
        assertEquals("a", batcher.accept("a"));
        clock.advance(50);
        assertNull(batcher.accept("b"));
        clock.advance(50);
        assertNull(batcher.accept("c"));
        clock.advance(50);
        assertEquals("bcd", batcher.accept("d"));
    }

    @Test
    void sizeCapFlushesWithoutWaitingForTheInterval() {
        final FakeClock clock = new FakeClock();
        clock.advance(10_000);
        final StreamBatcher batcher = batcher(clock);
        assertEquals("a", batcher.accept("a"));
        // only 50 ms after the previous flush - but the buffer reached the
        // size cap, so the cap wins and the wait never happens
        clock.advance(50);
        final String batch = batcher.accept("x".repeat(200));
        assertEquals(200, batch.length());
        assertEquals("x".repeat(200), batch);
    }

    @Test
    void flushResetsBufferAndTimerSeparately() {
        final FakeClock clock = new FakeClock();
        clock.advance(10_000);
        final StreamBatcher batcher = batcher(clock);
        assertEquals("a", batcher.accept("a"));
        // interval elapsed: flush takes ONLY what arrived since the last one
        clock.advance(150);
        assertEquals("b", batcher.accept("b"));
        // fresh timer: the very next small chunk waits again
        clock.advance(100);
        assertNull(batcher.accept("c"));
    }

    @Test
    void emptySweepProducesNoBatch() {
        final FakeClock clock = new FakeClock();
        final StreamBatcher batcher = batcher(clock);
        // nothing ever arrived and the interval fired on an empty buffer:
        // the timer moves but the page must not see an empty push
        clock.advance(150);
        assertNull(batcher.accept(""));
        clock.advance(151);
        assertEquals("late", batcher.accept("late"));
    }
}
