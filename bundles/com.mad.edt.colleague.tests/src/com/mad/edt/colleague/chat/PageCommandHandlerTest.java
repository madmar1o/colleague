package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.chat.PageCommandHandler.Actions;

/**
 * Pins the bridge dispatch table: which page method reaches which action, how
 * the page-supplied arguments travel to them, that the focus-state line is
 * rendered in a fixed order, and that unknown methods are ignored instead of
 * reaching some fallback action.
 */
class PageCommandHandlerTest {

    // the page method names double as the recorded action names, so one
    // constant per command serves the dispatch call and the expected line
    private static final String BRIDGE_READY = "bridgeReady";
    private static final String SEND_PREFIX = "send|";
    private static final String NEW_CHAT = "newChat";
    private static final String TOGGLE_EDIT = "toggleEdit";
    private static final String ATTACH_FILE = "attachFile";
    private static final String ABORT = "abort";
    private static final String CONTINUE_TURN = "continueTurn";
    private static final String OPEN_SETTINGS = "openSettings";
    private static final String SEND = "send";
    private static final String COPY = "copy";
    private static final String BACK = "back";
    // argument names repeated across the argument fixtures
    private static final String ARG_TEXT = "text";

    /** records every action the dispatcher triggers */
    private static final class RecordingActions implements Actions {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void bridgeReady() {
            calls.add(BRIDGE_READY);
        }

        @Override
        public void pageStateChanged(final String theState) {
            calls.add("pageState|" + theState);
        }

        @Override
        public void send(final String theText) {
            calls.add(SEND_PREFIX + theText);
        }

        @Override
        public void newChat() {
            calls.add(NEW_CHAT);
        }

        @Override
        public void toggleEdit() {
            calls.add(TOGGLE_EDIT);
        }

        @Override
        public void copy(final String theText) {
            calls.add("copy|" + theText);
        }

        @Override
        public void attachFile() {
            calls.add(ATTACH_FILE);
        }

        @Override
        public void back() {
            calls.add(BACK);
        }

        @Override
        public void escape() {
            calls.add("escape");
        }

        @Override
        public void abort() {
            calls.add(ABORT);
        }

        @Override
        public void diagnose(final String theText) {
            calls.add("diagnose|" + theText);
        }

        @Override
        public void pickProfile(final String theName) {
            calls.add("pickProfile|" + theName);
        }

        @Override
        public void continueTurn() {
            calls.add(CONTINUE_TURN);
        }

        @Override
        public void setMode(final String theName) {
            calls.add("setMode|" + theName);
        }

        @Override
        public void openSettings() {
            calls.add(OPEN_SETTINGS);
        }

        @Override
        public void openInEditor(final String thePath, final int theLine) {
            calls.add("openInEditor|" + thePath + "|" + theLine);
        }
    }

    /** page arguments as name-value pairs, missing name arrives as null */
    private static UnaryOperator<String> args(final Object... theNameValuePairs) {
        final Map<String, String> values = new HashMap<>();
        for(int index = 0; index < theNameValuePairs.length; index += 2) {
            values.put((String)theNameValuePairs[index], (String)theNameValuePairs[index + 1]);
        }
        return values::get;
    }

    private static PageCommandHandler handlerWith(final RecordingActions theActions) {
        return new PageCommandHandler(theActions);
    }

    @Test
    void everyKnownMethodReachesItsAction() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle(BRIDGE_READY, args());
        handler.handle(SEND, args(ARG_TEXT, "hello"));
        handler.handle(NEW_CHAT, args());
        handler.handle(TOGGLE_EDIT, args());
        handler.handle(COPY, args(ARG_TEXT, "clip me"));
        handler.handle(ATTACH_FILE, args());
        handler.handle(BACK, args());
        handler.handle("esc", args());
        handler.handle(ABORT, args());
        assertEquals(List.of(BRIDGE_READY, "send|hello", NEW_CHAT, TOGGLE_EDIT,
            "copy|clip me", ATTACH_FILE, BACK, "escape", ABORT), actions.calls);
    }

    @Test
    void unknownMethodsIgnored() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("teleport", args(ARG_TEXT, "x"));
        handler.handle("", args());
        handler.handle(null, args());
        handler.handle(SEND, null);
        assertEquals(List.of(SEND_PREFIX), actions.calls);
    }

    @Test
    void focusStateRendersFixedFieldOrder() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("sync", args("ae", "chat-input", "hf", "true", "kd", "Enter",
            "qvlen", "12", "ch", "3", "clicked", "send-button"));
        assertEquals(List.of("pageState|ae=chat-input hf=true kd=Enter qvlen=12 ch=3 "
            + "clicked=send-button"), actions.calls);
    }

    @Test
    void focusStateUsesEmptyValuesWhenAbsent() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("sync", args("ae", "chat-input"));
        assertEquals(List.of("pageState|ae=chat-input hf= kd= qvlen= ch= clicked="),
            actions.calls);
    }

    @Test
    void diagnosisCarriesTheReportedText() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("diag", args("txt", "bw=96 avatarOverlapsMeta=false"));
        assertEquals(List.of("diagnose|bw=96 avatarOverlapsMeta=false"), actions.calls);
    }

    @Test
    void profileChipChoiceCarriesTheProfileName() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("pickProfile", args("name", "reviewer"));
        assertEquals(List.of("pickProfile|reviewer"), actions.calls);
    }

    @Test
    void continueButtonPicksTheInterruptedTurnBackUp() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle(CONTINUE_TURN, args());
        assertEquals(List.of(CONTINUE_TURN), actions.calls);
    }

    @Test
    void modeSegmentCarriesTheModeNameToThePanel() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("setMode", args("mode", "plan"));
        handler.handle("setMode", args());
        assertEquals(List.of("setMode|plan", "setMode|"), actions.calls,
            "the page sends the mode name, a missing one arrives empty");
    }

    @Test
    void gearOpensTheProviderAndModelSettings() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle(OPEN_SETTINGS, args());
        assertEquals(List.of(OPEN_SETTINGS), actions.calls);
    }

    @Test
    void fileLinkClickCarriesPathAndLineToThePanel() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("openFile", args("path", "Catalogs/Orders/Orders.xml", "line", "7"));
        handler.handle("openFile", args("path", "Catalogs/Orders/Orders.xml"));
        assertEquals(List.of("openInEditor|Catalogs/Orders/Orders.xml|7",
            "openInEditor|Catalogs/Orders/Orders.xml|0"), actions.calls,
            "the page sends path and line, a missing line arrives as zero");
    }

    @Test
    void missingArgumentArrivesAsEmptyText() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle(SEND, args());
        handler.handle(COPY, args());
        assertEquals(List.of(SEND_PREFIX, "copy|"), actions.calls);
    }
}
