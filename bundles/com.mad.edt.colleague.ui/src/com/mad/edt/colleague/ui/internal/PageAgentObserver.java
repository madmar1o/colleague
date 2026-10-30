package com.mad.edt.colleague.ui.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentObserver;

/**
 * Agent trace that also feeds the chat page: every executed tool becomes a
 * chip carrying the full trace of its call, and every model round-trip that
 * asks for tools opens a trace block those chips nest into. The console trace
 * keeps running alongside, so an unattended run stays auditable exactly as
 * before; the page side only adds the progress the user needs while a long
 * reasoning pass keeps the answer undecided.
 */
final class PageAgentObserver implements AgentObserver {

    /** Longest argument flavour a chip carries, in characters. */
    private static final int DETAIL_CHARS = 48;
    /**
     * Longest result preview a chip carries, in characters. The full tool
     * output can be a file dump; the page only needs enough to judge the
     * call, the rest would flood the bridge.
     */
    private static final int RESULT_PREVIEW_CHARS = 400;

    private final AgentObserver ourTrace;
    private final Consumer<JsonObject> ourChipSink;
    private final Consumer<String> ourStatusSink;
    private final Consumer<JsonObject> ourTraceBlockSink;

    /**
     * @param theTrace console-side trace to keep feeding, {@code null} for none
     * @param theChipSink receives one page chip per executed tool call
     * @param theStatusSink receives one page status line per answer round
     * @param theTraceBlockSink receives one trace-block opening per round
     *            that asks for tools; its chips follow on the same bridge
     *            queue right after
     */
    PageAgentObserver(final AgentObserver theTrace, final Consumer<JsonObject> theChipSink,
        final Consumer<String> theStatusSink, final Consumer<JsonObject> theTraceBlockSink) {
        ourTrace = theTrace == null ? AgentObserver.NOOP : theTrace;
        ourChipSink = theChipSink;
        ourStatusSink = theStatusSink;
        ourTraceBlockSink = theTraceBlockSink;
    }

    /**
     * Opens a trace block for one model round-trip on the page: the block
     * header states the round and how many tool calls it asked for, and the
     * chips of that round nest inside it, each expandable to the arguments
     * and the result preview the call left behind. A round that asks for
     * nothing is the model formulating its answer: nothing to group, so it
     * stays a plain status line.
     *
     * @param theRound 1-based round-trip number
     * @param theRequestedToolCalls how many calls the round asked for
     */
    @Override
    public void onRound(final int theRound, final int theRequestedToolCalls) {
        ourTrace.onRound(theRound, theRequestedToolCalls);
        if(theRequestedToolCalls == 0) {
            ourStatusSink.accept("Раунд " + theRound + ": модель формулирует ответ...");
            return;
        }
        final JsonObject block = new JsonObject();
        block.addProperty("round", Integer.valueOf(theRound));
        block.addProperty("calls", Integer.valueOf(theRequestedToolCalls));
        ourTraceBlockSink.accept(block);
    }

    @Override
    public void onToolCall(final int theStep, final String theName,
        final String theArgumentsJson, final String theResult, final long theElapsedMs) {
        ourTrace.onToolCall(theStep, theName, theArgumentsJson, theResult, theElapsedMs);
        final JsonElement arguments = parse(theArgumentsJson);
        final JsonObject chip = new JsonObject();
        chip.addProperty("name", theName);
        chip.addProperty("detail", detail(arguments));
        chip.addProperty("state", "done");
        // the expandable details the page paints under the chip on click:
        // what the model asked for verbatim, what came back, and the cost
        chip.addProperty("args", theArgumentsJson == null ? "" : theArgumentsJson);
        chip.addProperty("result", preview(theResult));
        chip.addProperty("elapsed", Long.valueOf(theElapsedMs));
        final String file = stringValue(arguments, "file");
        if(!file.isEmpty()) {
            chip.addProperty("file", file);
        } else {
            final String path = stringValue(arguments, "path");
            if(!path.isEmpty()) {
                chip.addProperty("file", path);
            }
        }
        ourChipSink.accept(chip);
    }

    /**
     * @param theResult full tool output, may be {@code null}
     * @return the output clipped to {@link #RESULT_PREVIEW_CHARS} with the
     *         cut marked, empty for a call that returned nothing
     */
    private static String preview(final String theResult) {
        if(theResult == null) {
            return "";
        }
        return theResult.length() <= RESULT_PREVIEW_CHARS ? theResult
            : theResult.substring(0, RESULT_PREVIEW_CHARS) + "…";
    }

    /**
     * Takes the first string argument as the chip flavour: for the workspace
     * tools that is the query, the pattern or the file the model asked about.
     *
     * @param theArguments parsed tool arguments, {@code null} when unreadable
     * @return clipped flavour text, empty when the call carries no string
     */
    private static String detail(final JsonElement theArguments) {
        if(theArguments == null || !theArguments.isJsonObject()) {
            return "";
        }
        final Iterator<Map.Entry<String, JsonElement>> fields =
            theArguments.getAsJsonObject().entrySet().iterator();
        while(fields.hasNext()) {
            final JsonElement value = fields.next().getValue();
            if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                final String text = value.getAsString();
                return text.length() <= DETAIL_CHARS ? text
                    : text.substring(0, DETAIL_CHARS) + "…";
            }
        }
        return "";
    }

    private static String stringValue(final JsonElement theArguments, final String theName) {
        if(theArguments == null || !theArguments.isJsonObject()) {
            return "";
        }
        final JsonElement value = theArguments.getAsJsonObject().get(theName);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : "";
    }

    /**
     * @param theJson argument text the model emitted
     * @return parsed arguments, {@code null} when the text is not JSON
     */
    private static JsonElement parse(final String theJson) {
        if(theJson == null || theJson.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(theJson);
        } catch (final RuntimeException ex) {
            return null;
        }
    }
}
