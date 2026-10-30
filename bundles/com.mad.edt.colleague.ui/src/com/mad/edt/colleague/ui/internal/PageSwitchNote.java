package com.mad.edt.colleague.ui.internal;

/**
 * One-line trace of a page switch of the Colleague view. The chat tabs and the
 * provider list share a single stack, so "the chat disappeared and the provider
 * list came up" is written by exactly one of a few call sites. Printing the
 * cause, the tab count and an optional detail next to the page name makes the
 * console log answer who moved the user, instead of leaving it to guesswork.
 */
public final class PageSwitchNote {

    private PageSwitchNote() {
    }

    /**
     * Formats the switch note for the console log.
     *
     * @param thePage page that becomes visible, {@code "chat tabs"} or
     *            {@code "contacts"}
     * @param theCause call site that requested the switch, short and stable
     * @param theTabCount chat tabs alive at that moment, {@code -1} when the
     *            folder is already disposed and cannot answer
     * @param theDetail extra context such as the model of the panel that
     *            navigated back; blank means "print nothing"
     * @return one line body, e.g.
     *         {@code contacts (cause=back arrow, tabs=2, detail=qwen3:4b)}
     */
    public static String describe(final String thePage, final String theCause,
        final int theTabCount, final String theDetail) {
        final StringBuilder note = new StringBuilder(thePage);
        note.append(" (cause=").append(theCause)
            .append(", tabs=").append(theTabCount);
        if(theDetail != null && !theDetail.isBlank()) {
            note.append(", detail=").append(theDetail.strip());
        }
        return note.append(')').toString();
    }
}
