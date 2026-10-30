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
 * chip in the transcript and every model round-trip becomes a status line.
 * The console trace keeps running alongside, so an unattended run stays
 * auditable exactly as before; the page side only adds the progress the user
 * needs while a long reasoning pass keeps the answer undecided.
 */
final class PageAgentObserver implements AgentObserver {

    /** Longest argument flavour a chip carries, in characters. */
    private static final int DETAIL_CHARS = 48;

    private final AgentObserver ourTrace;
    private final Consumer<JsonObject> ourChipSink;
    private final Consumer<String> ourStatusSink;

    /**
     * @param theTrace console-side trace to keep feeding, {@code null} for none
     * @param theChipSink receives one page chip per executed tool call
     * @param theStatusSink receives one page status line per model round-trip
     */
    PageAgentObserver(final AgentObserver theTrace, final Consumer<JsonObject> theChipSink,
        final Consumer<String> theStatusSink) {
        ourTrace = theTrace == null ? AgentObserver.NOOP : theTrace;
        ourChipSink = theChipSink;
        ourStatusSink = theStatusSink;
    }

    @Override
    public void onRound(final int theStep, final int theRequestedToolCalls) {
        ourTrace.onRound(theStep, theRequestedToolCalls);
        ourStatusSink.accept(theRequestedToolCalls == 0
            ? "Раунд " + theStep + ": модель формулирует ответ..."
            : "Раунд " + theStep + ": модель просит инструменты, " + theRequestedToolCalls);
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
