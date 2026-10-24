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
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * Shared embedded-browser plumbing for the chat and contacts panels. The page
 * calls Java by invoking {@code window.colleague(method, jsonArgs)} — a
 * function contributed by an async batch of the browser backend — and takes
 * the result of that call as an array of {@code [kind, json]} event batches
 * that it feeds into its own {@code colleaguePush}. The very same array is
 * returned for a {@code sync} poll, which covers events Java produced on its
 * own (answers, status changes). As a fallback for backends without function
 * support, the page may navigate to
 * {@code colleague:<method>:<urlencoded json>}; those navigations are vetoed
 * and dispatched here. Events pushed before the page announced itself are
 * queued and delivered with the first reply.
 */
final class BridgeBrowser {

    /** Prefix of the navigation-based fallback call channel. */
    private static final String PROTOCOL = "colleague:";

    private final Browser ourBrowser;
    private final BiConsumer<String, JsonObject> ourHandler;
    private final List<String[]> ourQueue = new ArrayList<>();
    private boolean ourReady;
    /** Set while a page function call is being served on the UI thread. */
    private boolean ourInPageCall;

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
        final org.eclipse.swt.browser.BrowserFunction function =
            new org.eclipse.swt.browser.BrowserFunction(ourBrowser, "colleague") {
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
     * Sends one event to the page: through the running function call when the
     * page is polling, through the queue until it does.
     *
     * @param theKind event name seen by the page
     * @param thePayload JSON payload of the event
     */
    void push(final String theKind, final JsonObject thePayload) {
        synchronized(ourQueue) {
            ourQueue.add(new String[] {theKind, thePayload.toString()});
        }
        if(ourReady && !ourInPageCall && !ourBrowser.isDisposed()) {
            drainQueue();
        }
    }

    /** @return {@code true} once the page announced its push endpoint */
    boolean isReady() {
        return ourReady;
    }

    /**
     * Called on the UI thread from page code with the async batch arguments.
     *
     * @param theArguments raw batch arguments seen by the backend
     * @return JSON array text of {@code [kind, json]} events the page should
     *         apply right now, possibly empty; returned as text because the
     *         backend marshals plain Java objects as proxies, not values
     */
    private String invokePageCall(final Object... theArguments) {
        final JsonArray events = new JsonArray();
        if(theArguments.length < 1 || !(theArguments[0] instanceof String)) {
            return events.toString();
        }
        final String method = (String)theArguments[0];
        final JsonObject arguments;
        ourInPageCall = true;
        try {
            if("sync".equals(method)) {
                arguments = new JsonObject();
                markReady();
            } else if(theArguments.length > 1 && theArguments[1] != null) {
                arguments = parseArgs(theArguments[1]);
            } else {
                arguments = new JsonObject();
            }
            if("bridgeReady".equals(method)) {
                markReady();
                handle(method, arguments);
            } else {
                handle(method, arguments);
            }
        } finally {
            ourInPageCall = false;
        }
        synchronized(ourQueue) {
            while(!ourQueue.isEmpty()) {
                final String[] entry = ourQueue.remove(0);
                final JsonArray event = new JsonArray();
                event.add(entry[0]);
                event.add(entry[1]);
                events.add(event);
            }
        }
        return events.toString();
    }

    /** Pushes queued events into the open async function call. */
    private void drainQueue() {
        if(ourBrowser.isDisposed() || ourBrowser.getDisplay().getThread()
            != Thread.currentThread()) {
            return;
        }
        final JsonArray events = new JsonArray();
        synchronized(ourQueue) {
            while(!ourQueue.isEmpty()) {
                final String[] entry = ourQueue.remove(0);
                final JsonArray event = new JsonArray();
                event.add(entry[0]);
                event.add(entry[1]);
                events.add(event);
            }
        }
        if(events.size() == 0) {
            return;
        }
        ourBrowser.execute("window.colleagueEvents("
            + events.toString().replace("<", "\\u003c") + ")");
    }

    private void markReady() {
        if(!ourReady) {
            ourReady = true;
            if(!ourInPageCall) {
                drainQueue();
            }
        }
    }

    private boolean loadPage(final String theResource) {
        try {
            final java.net.URL entry = org.eclipse.core.runtime.Platform
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
        if("bridgeReady".equals(method)) {
            markReady();
        }
        handle(method, parseArgs(json));
    }

    private void handle(final String theMethod, final JsonObject theArguments) {
        ourHandler.accept(theMethod, theArguments);
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
