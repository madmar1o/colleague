package com.mad.edt.colleague.ui.internal;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * Shared embedded-browser plumbing for the chat and contacts panels. Two call
 * channels are supported because the browser backends differ in when they
 * expose host objects to a document written without a real navigation:
 * <ul>
 * <li>{@code window.colleague(method, jsonArgs)} — a host function whose
 * invocation Java serves directly; it may only appear after the first
 * intercepted {@code colleague:} navigation on some backends;</li>
 * <li>a vetoed navigation to
 * {@code colleague:<method>:<urlencoded json>} — always delivered.</li>
 * </ul>
 * Events flow one way only: Java queues them and answers every incoming call
 * (on either channel) with the batch of queued {@code [kind, json]} events as
 * the value it returns from the function. The page polls with
 * {@code colleague("sync", "{}")} whenever it has no call of its own to make,
 * so Java never has to run script inside the page.
 */
final class BridgeBrowser {

    /** Prefix of the navigation-based call channel. */
    private static final String PROTOCOL = "colleague:";

    private final Browser ourBrowser;
    private final BiConsumer<String, JsonObject> ourHandler;
    private final List<String[]> ourQueue = new ArrayList<>();
    private boolean ourReady;
    private boolean ourFuncSeen;
    private boolean ourLocationSeen;
    private boolean ourDeliverySeen;

    /**
     * @param theParent parent composite; the browser fills it via layout data
     * @param theResource bundle path of the page, e.g. {@code web/chat.html}
     * @param theHandler receives {@code (method, arguments)} on the UI thread;
     *            must answer {@code bridgeReady} by pushing any page data
     */
    BridgeBrowser(final Composite theParent, final String theResource,
        final BiConsumer<String, JsonObject> theHandler) {
        ourHandler = theHandler;
        ourBrowser = new Browser(theParent, SWT.NONE);
        ourBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ourBrowser.addLocationListener(new LocationListener() {
            @Override
            public void changing(final LocationEvent theEvent) {
                final String location = theEvent.location;
                if(location == null || !location.startsWith(PROTOCOL)) {
                    return;
                }
                theEvent.doit = false;
                dispatchLocation(location);
            }

            @Override
            public void changed(final LocationEvent theEvent) {
                // not needed: the page never really navigates
            }
        });
        final BrowserFunction function = new BrowserFunction(ourBrowser, "colleague") {
            @Override
            public Object function(final Object... theArguments) {
                return invokePageCall(theArguments);
            }
        };
        if(!loadPage(theResource)) {
            function.dispose();
            throw new IllegalStateException("page load failed: " + theResource);
        }
    }

    /**
     * @return the browser control
     */
    Browser getControl() {
        return ourBrowser;
    }

    /**
     * Queues one event for the page. It leaves with the reply to the next
     * call the page makes.
     *
     * @param theKind event name seen by the page
     * @param thePayload JSON payload of the event
     */
    void push(final String theKind, final JsonObject thePayload) {
        synchronized(ourQueue) {
            ourQueue.add(new String[] {theKind, thePayload.toString()});
        }
    }

    /** @return {@code true} once the page announced itself on any channel */
    boolean isReady() {
        return ourReady;
    }

    /**
     * Runs script inside the page. Reserved for self-checks that replay a
     * user gesture; ordinary events travel as call replies instead.
     *
     * @param theScript script body to evaluate in the page
     */
    void executeScript(final String theScript) {
        if(!ourBrowser.isDisposed()) {
            ourBrowser.execute(theScript);
        }
    }

    /**
     * Serves one {@code window.colleague} invocation from the page.
     *
     * @param theArguments raw arguments seen by the backend
     * @return JSON array text of {@code [kind, json]} events collected while
     *         the call was served; returned as text because the backends
     *         marshal complex Java values as proxies rather than plain values
     */
    private String invokePageCall(final Object... theArguments) {
        if(theArguments.length < 1 || !(theArguments[0] instanceof String)) {
            return new JsonArray().toString();
        }
        final String method = (String)theArguments[0];
        JsonObject arguments = new JsonObject();
        if(!"sync".equals(method) && theArguments.length > 1
            && theArguments[1] != null) {
            arguments = parseArgs(theArguments[1]);
        }
        if("bridgeReady".equals(method) || "sync".equals(method)) {
            ourReady = true;
        }
        if(!ourFuncSeen) {
            ourFuncSeen = true;
            System.out.println("[colleague] bridge: first page call via function: "
                + method);
        }
        handle(method, arguments);
        return drainBatch();
    }

    /**
     * Removes every queued event and renders it as the JSON batch the page
     * applies through its own {@code colleaguePush}.
     *
     * @return {@code [["kind","{json}"],...]} text, possibly {@code []}
     */
    private String drainBatch() {
        final JsonArray events = new JsonArray();
        synchronized(ourQueue) {
            for(final String[] entry : ourQueue) {
                final JsonArray event = new JsonArray();
                event.add(entry[0]);
                event.add(entry[1]);
                events.add(event);
            }
            ourQueue.clear();
        }
        final String batch = events.toString();
        if(!"[]".equals(batch) && !ourDeliverySeen) {
            ourDeliverySeen = true;
            System.out.println("[colleague] bridge: first event batch delivered to the page");
        }
        return batch;
    }

    private boolean loadPage(final String theResource) {
        try {
            final java.net.URL entry = ColleagueUIPlugin.PLUGIN_ID == null
                ? null : org.eclipse.core.runtime.Platform
                    .getBundle(ColleagueUIPlugin.PLUGIN_ID).getEntry(theResource);
            if(entry == null) {
                System.err.println("colleague: " + theResource
                    + " missing from the bundle");
                return false;
            }
            final StringBuilder text = new StringBuilder();
            try (InputStream stream = entry.openStream();
                InputStreamReader reader = new InputStreamReader(stream,
                    StandardCharsets.UTF_8)) {
                final char[] buffer = new char[8192];
                int read;
                while((read = reader.read(buffer)) > 0) {
                    text.append(buffer, 0, read);
                }
            }
            if(!ourBrowser.setText(text.toString())) {
                System.err.println("colleague: setText on the browser was refused");
                return false;
            }
            return true;
        } catch (final Exception ex) {
            System.err.println("colleague: page load failed: " + ex.getMessage());
            return false;
        }
    }

    /** Serves a call that arrived as a vetoed {@code colleague:} navigation. */
    private void dispatchLocation(final String theLocation) {
        final String rest = theLocation.substring(PROTOCOL.length());
        final int split = rest.indexOf(':');
        final String method = split < 0 ? rest : rest.substring(0, split);
        String json = "";
        if(split >= 0) {
            final String encoded = rest.substring(split + 1);
            try {
                json = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
            } catch (final Exception ex) {
                json = encoded;
            }
        }
        if("bridgeReady".equals(method) || "sync".equals(method)) {
            ourReady = true;
        }
        if(!ourLocationSeen) {
            ourLocationSeen = true;
            System.out.println("[colleague] bridge: first page call via navigation: "
                + method);
        }
        if("sync".equals(method)) {
            syncPollViaLocation();
            return;
        }
        handle(method, parseArgs(json));
    }

    /**
     * A polling call cannot be answered on the navigation channel; its events
     * are handed over with the next function call, or in a pinch run through
     * a script that the backend does accept on a navigated document.
     */
    private void syncPollViaLocation() {
        if(ourBrowser.isDisposed()) {
            return;
        }
        final String batch = drainBatch();
        if("[]".equals(batch)) {
            return;
        }
        ourBrowser.execute("window.colleagueEvents(" + batch.replace("<", "\\u003c")
            + ")");
    }

    /**
     * Runs the panel handler after the current page call has been answered.
     * Handlers may open new tabs and create new embedded browsers; a browser
     * must never be created inside a page callback — the backend deadlocks.
     */
    private void handle(final String theMethod, final JsonObject theArguments) {
        if(ourBrowser.isDisposed()) {
            return;
        }
        ourBrowser.getDisplay().timerExec(0, () -> {
            if(!ourBrowser.isDisposed()) {
                ourHandler.accept(theMethod, theArguments);
            }
        });
    }

    static JsonObject parseArgs(final Object theArgument) {
        if(theArgument instanceof String) {
            try {
                final com.google.gson.JsonElement parsed =
                    JsonParser.parseString((String)theArgument);
                if(parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (final RuntimeException ignored) {
                // fall through to an empty object
            }
        }
        return new JsonObject();
    }

    static String optString(final JsonObject theObject, final String theName) {
        return theObject.has(theName) && !theObject.get(theName).isJsonNull()
            ? theObject.get(theName).getAsString() : "";
    }
}
