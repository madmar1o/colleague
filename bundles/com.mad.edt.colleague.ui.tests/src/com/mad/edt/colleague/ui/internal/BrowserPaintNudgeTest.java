package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests the geometry nudge that makes the embedded browser present a frame
 * a DOM-only change just made: the webview surface keeps showing the
 * previous paint until the control's size changes, so the fill takes one
 * pixel off the width and lets the layout put it back. A control too
 * narrow to spare a pixel must not be squeezed.
 */
class BrowserPaintNudgeTest {

    @Test
    void aNormalWidthSparesOnePixel() {
        assertEquals(99, BrowserPaintNudge.nudgedWidth(100));
    }

    @Test
    void theFloorWidthStillSparesAPixel() {
        assertEquals(BrowserPaintNudge.MIN_WIDTH - 1,
            BrowserPaintNudge.nudgedWidth(BrowserPaintNudge.MIN_WIDTH));
    }

    @Test
    void aTooNarrowControlIsLeftAlone() {
        assertEquals(BrowserPaintNudge.MIN_WIDTH - 1,
            BrowserPaintNudge.nudgedWidth(BrowserPaintNudge.MIN_WIDTH - 1));
        assertEquals(0, BrowserPaintNudge.nudgedWidth(0));
    }
}
