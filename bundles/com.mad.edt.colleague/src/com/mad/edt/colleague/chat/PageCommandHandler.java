package com.mad.edt.colleague.chat;

import java.util.function.Function;

/**
 * Dispatch table for the chat page's bridge calls: turns the method name and
 * the page-supplied arguments into one typed action callback, so the user
 * gesture routing can be pinned by tests while the actions themselves stay
 * where their toolkits live. Not reentrant; each panel drives one handler
 * from its UI thread.
 */
public final class PageCommandHandler {

    /**
     * The panel-side effects one bridge call can request.
     */
    public interface Actions {

        /** the page announced itself on the bridge */
        void bridgeReady();

        /**
         * The page reports its focus state as one pre-rendered line.
         *
         * @param theState state line in the fixed field order
         */
        void pageStateChanged(String theState);

        /**
         * The page input control submitted a question.
         *
         * @param theText question text, empty when the page sent none
         */
        void send(String theText);

        /** start a fresh conversation */
        void newChat();

        /** switch the edit mode of the assistant on or off */
        void toggleEdit();

        /**
         * Copy page text to the system clipboard.
         *
         * @param theText text to copy, empty when the page sent none
         */
        void copy(String theText);

        /** the page asked for file attachment */
        void attachFile();

        /** navigate back to the contact list */
        void back();

        /** the page wants the editor escape gesture under its control */
        void escape();

        /** stop the running generation */
        void abort();

        /**
         * The page reports a layout measurement for regression proofs.
         *
         * @param theText measured text, empty when the page sent none
         */
        void diagnose(String theText);

        /**
         * The profile chip picked one of the workspace agent profiles.
         *
         * @param theName profile name to switch to, empty to drop back to the
         *            default agent
         */
        void pickProfile(String theName);

        /**
         * The page picked one of the three chat modes directly: plain
         * question, planning pass or full execution.
         *
         * @param theName mode name on the page wire, unknown names settle on
         *            the safest mode
         */
        void setMode(String theName);

        /**
         * The gear in the chat header was clicked: the view shows the page
         * where providers and their models are configured.
         */
        void openSettings();

        /**
         * The page asked to pick an interrupted agent turn back up: the
         * carried conversation continues over the same history instead of a
         * fresh question.
         */
        void continueTurn();
    }

    private static final String[] FOCUS_STATE_FIELDS = {"ae", "hf", "kd", "qvlen", "ch",
        "clicked"};

    private final Actions actions;

    /**
     * @param theActions panel-side effects to trigger
     */
    public PageCommandHandler(final Actions theActions) {
        actions = theActions;
    }

    /**
     * Routes one page call. Unknown method names are ignored.
     *
     * @param theMethod page method name, may be {@code null}
     * @param theArgument reads one named page argument, empty string when the
     *            page sent none
     */
    public void handle(final String theMethod, final Function<String, String> theArgument) {
        if(theMethod == null) {
            return;
        }
        switch(theMethod) {
            case "bridgeReady":
                actions.bridgeReady();
                return;
            case "sync":
                actions.pageStateChanged(focusState(theArgument));
                return;
            case "send":
                actions.send(argument(theArgument, "text"));
                return;
            case "newChat":
                actions.newChat();
                return;
            case "toggleEdit":
                actions.toggleEdit();
                return;
            case "copy":
                actions.copy(argument(theArgument, "text"));
                return;
            case "attachFile":
                actions.attachFile();
                return;
            case "back":
                actions.back();
                return;
            case "esc":
                actions.escape();
                return;
            case "abort":
                actions.abort();
                return;
            case "diag":
                actions.diagnose(argument(theArgument, "txt"));
                return;
            case "pickProfile":
                actions.pickProfile(argument(theArgument, "name"));
                return;
            case "continueTurn":
                actions.continueTurn();
                return;
            case "setMode":
                actions.setMode(argument(theArgument, "mode"));
                return;
            case "openSettings":
                actions.openSettings();
                return;
            default:
                return;
        }
    }

    /**
     * Renders the page focus state in the fixed field order the diagnostics
     * grepping relies on.
     *
     * @param theArgument page argument reader
     * @return one state line
     */
    private static String focusState(final Function<String, String> theArgument) {
        final StringBuilder state = new StringBuilder();
        for(final String field : FOCUS_STATE_FIELDS) {
            if(state.length() > 0) {
                state.append(' ');
            }
            state.append(field).append('=').append(argument(theArgument, field));
        }
        return state.toString();
    }

    private static String argument(final Function<String, String> theArgument,
        final String theName) {
        final String value = theArgument == null ? null : theArgument.apply(theName);
        return value == null ? "" : value;
    }
}
