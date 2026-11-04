package com.mad.edt.colleague.chat;

/**
 * Carries one question staged for a chat page that has not announced itself
 * on the bridge yet. The page only defines its event entry point some time
 * after the panel is built, so an input event pushed too early is dropped by
 * the browser; staging the text here lets the panel hand it out exactly once
 * when the page reports ready. Not thread-safe: the panel stages and drains
 * on its own UI thread.
 */
public final class PendingInputFill {

    private String ourText = "";

    /**
     * Remembers the text to place into the page's question box. A blank text
     * clears nothing and is ignored; a later stage replaces the earlier one.
     *
     * @param theText question text to stage, blank stages nothing
     */
    public void stage(final String theText) {
        if(theText != null && !theText.trim().isEmpty()) {
            ourText = theText;
        }
    }

    /**
     * Takes the staged text out: the next drain answers empty until a new
     * stage arrives.
     *
     * @return the staged text, empty when nothing waits
     */
    public String drain() {
        final String text = ourText;
        ourText = "";
        return text;
    }
}
