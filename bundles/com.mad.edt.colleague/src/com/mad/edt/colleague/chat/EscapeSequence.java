package com.mad.edt.colleague.chat;

import java.util.function.LongSupplier;

/**
 * The double-ESC gesture that switches the edit mode on and off. One press
 * arms the pair; a second press within the window completes it. The same
 * physical key often reaches the panel twice - once through the workbench
 * key filter and once through the page bridge - so presses closer together
 * than the dedup pause count as a single one. Kept free of SWT types so the
 * timing can be tested with a clock under control.
 */
public final class EscapeSequence {

    /** what the panel should do after a press lands in this gesture */
    public enum Result {
        /** the same keypress seen again inside the dedup pause: drop it */
        IGNORED,
        /** first press of a pair: wait for the second one */
        ARMED,
        /** the pair completed inside the window: toggle the mode */
        TOGGLED
    }

    private final long myWindowMs;
    private final long myDedupMs;
    private final LongSupplier myClock;

    private long myLastAcceptedAt;
    private long myLastArmedAt;

    public EscapeSequence(final long theWindowMs, final long theDedupMs) {
        this(theWindowMs, theDedupMs, System::currentTimeMillis);
    }

    public EscapeSequence(final long theWindowMs, final long theDedupMs,
        final LongSupplier theClock) {
        myWindowMs = theWindowMs;
        myDedupMs = theDedupMs;
        myClock = theClock;
    }

    /**
     * Reports one observed ESC press, from whichever channel delivered it.
     *
     * @return what the caller should do about it
     */
    public synchronized Result press() {
        final long now = myClock.getAsLong();
        if(now - myLastAcceptedAt < myDedupMs) {
            return Result.IGNORED;
        }
        myLastAcceptedAt = now;
        if(myLastArmedAt > 0L && now - myLastArmedAt < myWindowMs) {
            myLastArmedAt = 0L;
            return Result.TOGGLED;
        }
        myLastArmedAt = now;
        return Result.ARMED;
    }
}
