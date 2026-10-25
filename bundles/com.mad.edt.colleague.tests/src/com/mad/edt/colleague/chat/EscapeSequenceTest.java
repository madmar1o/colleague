package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the double-ESC gesture: one press arms the toggle, a second press
 * inside the window fires it, and the same physical key arriving twice
 * within the dedup pause counts once.
 */
class EscapeSequenceTest {

    private static final class FakeClock {
        private long myNow;

        long now() {
            return myNow;
        }

        void advance(final long theMs) {
            myNow += theMs;
        }
    }

    private static EscapeSequence gesture(final FakeClock theClock) {
        return new EscapeSequence(2_000L, 300L, theClock::now);
    }

    @Test
    void firstPressArmsTheToggle() {
        final FakeClock clock = new FakeClock();
        clock.advance(10_000);
        assertEquals(EscapeSequence.Result.ARMED, gesture(clock).press());
    }

    @Test
    void duplicatedKeyInsideTheDedupPauseCountsOnce() {
        final FakeClock clock = new FakeClock();
        final EscapeSequence gesture = gesture(clock);
        clock.advance(10_000);
        assertEquals(EscapeSequence.Result.ARMED, gesture.press());
        // the SWT filter and the page bridge both report the same keypress
        clock.advance(200);
        assertEquals(EscapeSequence.Result.IGNORED, gesture.press());
        // still inside 300 ms measured from the ACCEPTED press, not from
        // the ignored duplicate
        clock.advance(99);
        assertEquals(EscapeSequence.Result.IGNORED, gesture.press());
    }

    @Test
    void secondPressInsideTheWindowToggles() {
        final FakeClock clock = new FakeClock();
        final EscapeSequence gesture = gesture(clock);
        clock.advance(10_000);
        assertEquals(EscapeSequence.Result.ARMED, gesture.press());
        // past the dedup pause, still inside the two second window
        clock.advance(301);
        assertEquals(EscapeSequence.Result.TOGGLED, gesture.press());
    }

    @Test
    void pressAfterTheWindowStaysExpiredArmsAgain() {
        final FakeClock clock = new FakeClock();
        final EscapeSequence gesture = gesture(clock);
        clock.advance(10_000);
        assertEquals(EscapeSequence.Result.ARMED, gesture.press());
        // one millisecond beyond the window: this press only re-arms
        clock.advance(2_001);
        assertEquals(EscapeSequence.Result.ARMED, gesture.press());
        // and the new arm can be completed as usual
        clock.advance(301);
        assertEquals(EscapeSequence.Result.TOGGLED, gesture.press());
    }

    @Test
    void toggledGestureNeedsAFreshPairNotASingleFollowUp() {
        final FakeClock clock = new FakeClock();
        final EscapeSequence gesture = gesture(clock);
        clock.advance(10_000);
        gesture.press();
        clock.advance(301);
        assertEquals(EscapeSequence.Result.TOGGLED, gesture.press());
        // a third press right after the toggle must NOT toggle back: the
        // window was cleared, so this press only arms a new pair
        clock.advance(301);
        assertEquals(EscapeSequence.Result.ARMED, gesture.press());
    }
}
