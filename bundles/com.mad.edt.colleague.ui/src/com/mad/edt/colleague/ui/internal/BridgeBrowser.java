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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.ui.ColleagueUIPlugin;

/**
 * Shared embedded-browser plumbing for the chat and contacts panels: loads a
 * bundled HTML page, then relays calls from the page to Java through two
 * channels: a named host object (fast path, may be unavailable depending on
 * the browser backend) and the {@code colleague:<method>:<urlencoded json>}
 * navigation protocol (always available, vetoed before it navigates).
 * Events travel back through {@code window.colleaguePush(kind, json)}; calls
 * issued before the page signals {@code bridgeReady} are queued.
 */
final class BridgeBrowser {

    /** Prefix of the navigation-based call channel. */
    private static final String PROTOCOL = "colleague:";

    private final Browser ourBrowser;
    private final BiConsumer<String, JsonObject> ourHandler;
    private final List<String> ourQueue = new ArrayList<>();
    private boolean ourReady;

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
                // the page re-registers after any real navigation
            }
        });
        new BridgeFunction(ourBrowser, "colleague");
        if(!loadPage(theResource)) {
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
     * Sends one event to the page, queueing it until the page is ready.
     *
     * @param theKind event name seen by the page
     * @param thePayload JSON payload of the event
     */
    void push(final String theKind, final JsonObject thePayload) {
        final String call = "window.colleaguePush('" + theKind + "','"
            + escapeJs(thePayload.toString()) + "')";
        if(!ourReady) {
            ourQueue.add(call);
            return;
        }
        if(ourBrowser.isDisposed()) {
            return;
        }
        if(ourBrowser.getDisplay().getThread() == Thread.currentThread()) {
            ourBrowser.execute(call);
        } else {
            ourBrowser.getDisplay().asyncExec(() -> {
                if(!ourBrowser.isDisposed()) {
                    ourBrowser.execute(call);
                }
            });
        }
    }

    /** @return {@code true} once the page announced its push endpoint */
    boolean isReady() {
        return ourReady;
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
        handle(method, parseArgs(json));
    }

    private void handle(final String theMethod, final JsonObject theArguments) {
        if("bridgeReady".equals(theMethod)) {
            if(ourReady) {
                return;
            }
            ourReady = true;
            final List<String> replay = new ArrayList<>(ourQueue);
            ourQueue.clear();
            for(final String call : replay) {
                if(!ourBrowser.isDisposed()) {
                    ourBrowser.execute(call);
                }
            }
        }
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

    private static String escapeJs(final String theText) {
        return theText.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\r", "\\r").replace("\n", "\\n").replace("<", "\\u003c");
    }

    /** Host object channel exposed to page scripts where supported. */
    private final class BridgeFunction extends BrowserFunction {

        private BridgeFunction(final Browser theBrowser, final String theName) {
            super(theBrowser, theName);
        }

        @Override
        public Object function(final Object... theArguments) {
            if(theArguments.length < 1 || !(theArguments[0] instanceof String)) {
                return Boolean.FALSE;
            }
            handle((String)theArguments[0],
                parseArgs(theArguments.length > 1 ? theArguments[1] : null));
            return Boolean.TRUE;
        }
    }
}
