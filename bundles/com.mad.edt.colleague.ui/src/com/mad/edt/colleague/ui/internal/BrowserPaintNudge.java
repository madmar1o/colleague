package com.mad.edt.colleague.ui.internal;

/**
 * Geometry nudge that makes the embedded browser present a frame a DOM-only
 * change just made. The webview surface keeps showing the previous paint
 * until the control's size changes - the same thing a hand-dragged pane
 * border does - so a fill that arrives without a user gesture sits
 * invisible in a form that looks untouched. The nudge takes one pixel off
 * the browser's width; the panel's layout puts it back on the next pass.
 */
final class BrowserPaintNudge {

    /**
     * A control narrower than this would be crowded by the missing pixel
     * more than the repaint is worth.
     */
    static final int MIN_WIDTH = 8;

    private BrowserPaintNudge() {
    }

    /**
     * @param theCurrentWidth current width of the browser control
     * @return the width to set for the nudge; the same width when the
     *         control is too narrow to spare a pixel
     */
    static int nudgedWidth(final int theCurrentWidth) {
        return theCurrentWidth >= MIN_WIDTH ? theCurrentWidth - 1 : theCurrentWidth;
    }
}
