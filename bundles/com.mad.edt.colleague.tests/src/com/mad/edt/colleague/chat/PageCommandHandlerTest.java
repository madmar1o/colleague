package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.chat.PageCommandHandler.Actions;

/**
 * Pins the bridge dispatch table: which page method reaches which action, how
 * the page-supplied arguments travel to them, that the focus-state line is
 * rendered in a fixed order, and that unknown methods are ignored instead of
 * reaching some fallback action.
 */
class PageCommandHandlerTest {

    /** records every action the dispatcher triggers */
    private static final class RecordingActions implements Actions {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void bridgeReady() {
            calls.add("bridgeReady");
        }

        @Override
        public void pageStateChanged(final String theState) {
            calls.add("pageState|" + theState);
        }

        @Override
        public void send(final String theText) {
            calls.add("send|" + theText);
        }

        @Override
        public void newChat() {
            calls.add("newChat");
        }

        @Override
        public void toggleEdit() {
            calls.add("toggleEdit");
        }

        @Override
        public void copy(final String theText) {
            calls.add("copy|" + theText);
        }

        @Override
        public void attachFile() {
            calls.add("attachFile");
        }

        @Override
        public void back() {
            calls.add("back");
        }

        @Override
        public void escape() {
            calls.add("escape");
        }

        @Override
        public void abort() {
            calls.add("abort");
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
            calls.add("continueTurn");
        }

        @Override
        public void setMode(final String theName) {
            calls.add("setMode|" + theName);
        }

        @Override
        public void openSettings() {
            calls.add("openSettings");
        }

        @Override
        public void openInEditor(final String thePath, final int theLine) {
            calls.add("openInEditor|" + thePath + "|" + theLine);
        }
    }

    /** page arguments as name-value pairs, missing name arrives as null */
    private static Function<String, String> args(final Object... theNameValuePairs) {
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
        handler.handle("bridgeReady", args());
        handler.handle("send", args("text", "hello"));
        handler.handle("newChat", args());
        handler.handle("toggleEdit", args());
        handler.handle("copy", args("text", "clip me"));
        handler.handle("attachFile", args());
        handler.handle("back", args());
        handler.handle("esc", args());
        handler.handle("abort", args());
        assertEquals(List.of("bridgeReady", "send|hello", "newChat", "toggleEdit",
            "copy|clip me", "attachFile", "back", "escape", "abort"), actions.calls);
    }

    @Test
    void unknownMethodsIgnored() {
        final RecordingActions actions = new RecordingActions();
        final PageCommandHandler handler = handlerWith(actions);
        handler.handle("teleport", args("text", "x"));
        handler.handle("", args());
        handler.handle(null, args());
        handler.handle("send", null);
        assertEquals(List.of("send|"), actions.calls);
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
        handler.handle("continueTurn", args());
        assertEquals(List.of("continueTurn"), actions.calls);
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
        handler.handle("openSettings", args());
        assertEquals(List.of("openSettings"), actions.calls);
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
        handler.handle("send", args());
        handler.handle("copy", args());
        assertEquals(List.of("send|", "copy|"), actions.calls);
    }
}
