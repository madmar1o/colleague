package com.mad.edt.colleague.ui.internal;

import com.mad.edt.colleague.ColleagueLog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import org.osgi.framework.FrameworkUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The debug web bridge: the chat page over a plain HTTP socket, so an agent or
 * a browser test can drive and watch a real chat panel without a workbench
 * window. It stays a debugging door, never a feature: it only opens when a port
 * was asked for with {@code -Dcolleague.webbridge.port=<port>}, it binds to the
 * loopback interface only, and every data request has to carry the one-time
 * token this bridge generated at start. The page itself is the very same
 * {@code web/chat.html} the embedded browser renders, served with a small
 * bootstrap in front of it that replaces the browser function with an HTTP call
 * and a long poll. The wire format of events is identical to the embedded
 * bridge ({@code [["kind","{json}"],...]} into {@code window.colleagueEvents}),
 * so nothing in the page distinguishes the two transports.
 */
public final class WebBridge implements PageTransport {

    /** Start-up flag naming the port to listen on; absent or 0 keeps it closed. */
    private static final String PORT_PROPERTY = "colleague.webbridge.port";

    /** Header every data request has to carry. */
    private static final String TOKEN_HEADER = "x-colleague-token";

    /** Loopback address the socket is bound to; never a reachable interface. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** How long an empty poll holds the request before answering with nothing. */
    private static final long POLL_WAIT_MS = 1_200L;

    /** Cap on one request body, so a stray client cannot exhaust the heap. */
    private static final int MAX_BODY_BYTES = 1 << 20;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ServerSocket ourSocket;
    private final String ourToken;
    private final String ourPage;
    private final List<Object[]> ourEvents = new ArrayList<>();
    private final Thread ourAcceptor;

    /** Media type of the plain-text error answers the bridge replies with. */
    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

    private volatile BiConsumer<String, JsonObject> ourPageCallSink;
    private volatile boolean ourOpen;

    /**
     * Opens the bridge.
     *
     * @param thePort port to listen on, {@code 0} to take any free one
     * @return the running bridge
     * @throws IOException when the loopback socket cannot be opened
     */
    public static WebBridge start(final int thePort) throws IOException {
        return new WebBridge(thePort);
    }

    /** @return the port the start-up flag asks for, {@code 0} when unconfigured */
    public static int requestedPort() {
        return Integer.getInteger(PORT_PROPERTY, Integer.valueOf(0)).intValue();
    }

    /**
     * @param theHost host name to judge
     * @return whether that host is a loopback address, i.e. reachable only from
     *         this machine
     */
    public static boolean isLoopback(final String theHost) {
        try {
            return InetAddress.getByName(theHost).isLoopbackAddress();
        } catch (final IOException ex) {
            return false;
        }
    }

    /**
     * @param thePort port to listen on, {@code 0} for an ephemeral one
     * @throws IOException when the socket cannot be opened
     */
    private WebBridge(final int thePort) throws IOException {
        ourToken = randomToken();
        ourSocket = new ServerSocket();
        boolean bound = false;
        try {
            ourSocket.bind(new InetSocketAddress(LOOPBACK_HOST, thePort));
            bound = true;
            ourPage = renderPage();
        } finally {
            if(!bound) {
                closeQuietly();
            }
        }
        ourOpen = true;
        ourAcceptor = new Thread(this::acceptLoop, "colleague-web-bridge");
        ourAcceptor.setDaemon(true);
        ourAcceptor.start();
    }

    /** @return the address the socket is bound to, loopback by construction */
    public String host() {
        return LOOPBACK_HOST;
    }

    /** @return the port actually in use, useful when {@code 0} was asked for */
    public int port() {
        return ourSocket.getLocalPort();
    }

    /** @return the secret every data request has to carry */
    public String token() {
        return ourToken;
    }

    /**
     * Says where page calls have to go. The sink runs on a bridge thread, so it
     * is the panel's job to move the work onto its own UI thread.
     *
     * @param theSink receives {@code (method, arguments)} of each page call,
     *            {@code null} to drop calls until a panel attaches
     */
    public void setPageCallSink(final BiConsumer<String, JsonObject> theSink) {
        ourPageCallSink = theSink;
    }

    @Override
    public void push(final String theKind, final JsonObject thePayload) {
        synchronized(ourEvents) {
            ourEvents.add(new Object[] {theKind,
                thePayload == null ? "{}" : thePayload.toString()});
            ourEvents.notifyAll();
        }
    }

    @Override
    public void pushEvents(final List<Object[]> theBatch) {
        synchronized(ourEvents) {
            if(theBatch != null && !theBatch.isEmpty()) {
                ourEvents.addAll(theBatch);
            }
            // an empty batch is a flush request: waking the poll is enough
            ourEvents.notifyAll();
        }
    }

    /** Stops listening and releases the port. */
    public void close() {
        ourOpen = false;
        closeQuietly();
        synchronized(ourEvents) {
            ourEvents.notifyAll();
        }
        if(ourAcceptor != null) {
            ourAcceptor.interrupt();
        }
    }

    /** Closes the socket without letting a shutdown race report an error. */
    private void closeQuietly() {
        try {
            ourSocket.close();
        } catch (final IOException ignored) {
            // already closed
        }
    }

    /** Accepts connections while the bridge is open; each one is served briefly. */
    private void acceptLoop() {
        while(ourOpen) {
            try {
                final Socket client = ourSocket.accept();
                final Thread worker = new Thread(() -> serve(client),
                    "colleague-web-bridge-connection");
                worker.setDaemon(true);
                worker.start();
            } catch (final IOException ex) {
                if(ourOpen) {
                    ColleagueLog.error("[colleague] web bridge accept failed: "
                        + ex.getMessage());
                }
                return;
            }
        }
    }

    /**
     * Reads one request from a connection and answers it, then closes the
     * connection: the debug page is a browser tab, and it re-requests on every
     * poll anyway.
     *
     * @param theClient accepted connection to serve
     */
    private void serve(final Socket theClient) {
        try (Socket socket = theClient) {
            socket.setSoTimeout(15_000);
            final InputStream in = socket.getInputStream();
            final String requestLine = readLine(in);
            if(requestLine == null) {
                return;
            }
            final String[] parts = requestLine.split(" ");
            if(parts.length < 2) {
                answer(socket.getOutputStream(), 400, TEXT_PLAIN_UTF8, "bad request");
                return;
            }
            final String method = parts[0].toUpperCase(Locale.ROOT);
            final String path = pathOf(parts[1]);
            final RequestHeaders headers = readHeaders(in);
            final String body = headers.ourBodyBytes > 0
                ? readBody(in, headers.ourBodyBytes) : "";
            route(socket.getOutputStream(), method, path, headers, body);
        } catch (final IOException ex) {
            // a browser tab drops polls all the time; that is not an error here
        }
    }

    /**
     * Sends one response.
     *
     * @param theOut connection output to write to
     * @param theMethod HTTP method of the request
     * @param thePath request path without the query
     * @param theHeaders parsed request headers
     * @param theBody request body text, empty when there was none
     * @throws IOException on connection failures
     */
    private void route(final OutputStream theOut, final String theMethod, final String thePath,
        final RequestHeaders theHeaders, final String theBody) throws IOException {
        if("/".equals(thePath) || "/index.html".equals(thePath)) {
            // the page carries the token inside it, so it has to be reachable
            // without one; everything that carries conversation data does not
            answer(theOut, 200, "text/html; charset=utf-8", ourPage);
            return;
        }
        if(!theHeaders.hasToken(ourToken)) {
            answer(theOut, 401, TEXT_PLAIN_UTF8, "token required");
            return;
        }
        if("/call".equals(thePath)) {
            handleCall(theOut, theMethod, theBody);
            return;
        }
        if("/events".equals(thePath)) {
            answer(theOut, 200, "application/json; charset=utf-8", pollEvents());
            return;
        }
        answer(theOut, 404, TEXT_PLAIN_UTF8, "not found");
    }

    /**
     * Hands one page call to the attached panel.
     *
     * @param theOut connection output to answer on
     * @param theMethod HTTP method of the request
     * @param theBody JSON text {@code {"m": method, "a": {arguments}}}
     * @throws IOException on connection failures
     */
    private void handleCall(final OutputStream theOut, final String theMethod, final String theBody)
        throws IOException {
        if(!"POST".equals(theMethod)) {
            answer(theOut, 405, TEXT_PLAIN_UTF8, "POST only");
            return;
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(theBody);
        } catch (final RuntimeException ex) {
            answer(theOut, 400, TEXT_PLAIN_UTF8, "body is not JSON");
            return;
        }
        if(parsed == null || !parsed.isJsonObject()) {
            answer(theOut, 400, TEXT_PLAIN_UTF8, "body is not a JSON object");
            return;
        }
        final JsonObject call = parsed.getAsJsonObject();
        final String name = text(call, "m");
        if(name.isEmpty()) {
            answer(theOut, 400, TEXT_PLAIN_UTF8, "no method in the call");
            return;
        }
        final JsonObject arguments = call.has("a") && call.get("a").isJsonObject()
            ? call.getAsJsonObject("a") : new JsonObject();
        final BiConsumer<String, JsonObject> sink = ourPageCallSink;
        if(sink != null) {
            sink.accept(name, arguments);
        } else {
            // the page is open but no chat tab has attached to this bridge yet:
            // saying so beats dropping a call without a trace
            ColleagueLog.info("[colleague] web bridge dropped page call '" + name
                + "': no chat tab attached");
        }
        // any event the panel produced while taking the call leaves with the
        // reply, the same way the embedded bridge answers a page call
        final String batch;
        synchronized(ourEvents) {
            batch = BridgePushDelivery.renderBatch(drainLocked());
        }
        answer(theOut, 200, "application/json; charset=utf-8", batch);
    }

    /**
     * Waits for queued events and renders them in the page wire format.
     *
     * @return {@code [["kind","{json}"],...]} text, {@code []} when nothing
     *         arrived while waiting
     */
    private String pollEvents() {
        final long deadline = System.currentTimeMillis() + POLL_WAIT_MS;
        synchronized(ourEvents) {
            while(ourEvents.isEmpty() && ourOpen) {
                final long left = deadline - System.currentTimeMillis();
                if(left <= 0) {
                    break;
                }
                try {
                    ourEvents.wait(left);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return BridgePushDelivery.renderBatch(drainLocked());
        }
    }

    /**
     * @return everything queued, clearing the queue; the caller holds the queue
     *         monitor
     */
    private List<Object[]> drainLocked() {
        final List<Object[]> batch = new ArrayList<>(ourEvents);
        ourEvents.clear();
        return batch;
    }

    /**
     * Writes one HTTP response with the connection closed afterwards.
     *
     * @param theOut connection output to write to
     * @param theStatus HTTP status to report
     * @param theType content type header value
     * @param theText body text
     * @throws IOException on connection failures
     */
    private static void answer(final OutputStream theOut, final int theStatus,
        final String theType, final String theText) throws IOException {
        final byte[] body = theText.getBytes(StandardCharsets.UTF_8);
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(theStatus).append(' ')
            .append(reasonOf(theStatus)).append("\r\n");
        head.append("Content-Type: ").append(theType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        theOut.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        theOut.write(body);
        theOut.flush();
    }

    /**
     * @param theStatus HTTP status code sent
     * @return the reason phrase to write next to it
     */
    private static String reasonOf(final int theStatus) {
        switch(theStatus) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            default:
                return "Server Error";
        }
    }

    /**
     * @param theIn connection input
     * @return one CRLF-terminated header line as text, {@code null} at once-end
     * @throws IOException on connection failures
     */
    private static String readLine(final InputStream theIn) throws IOException {
        final StringBuilder line = new StringBuilder();
        int current;
        while((current = theIn.read()) >= 0) {
            if(current == '\n') {
                return line.toString();
            }
            if(current != '\r') {
                line.append((char)current);
            }
        }
        return line.length() == 0 ? null : line.toString();
    }

    /**
     * Reads the header block of one request.
     *
     * @param theIn connection input
     * @return the headers that matter here: the token and the body length
     * @throws IOException on connection failures
     */
    private static RequestHeaders readHeaders(final InputStream theIn) throws IOException {
        final RequestHeaders headers = new RequestHeaders();
        String line;
        while((line = readLine(theIn)) != null && !line.isEmpty()) {
            final int colon = line.indexOf(':');
            if(colon <= 0) {
                continue;
            }
            final String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(colon + 1).trim();
            if(TOKEN_HEADER.equals(name)) {
                headers.ourToken = value;
            } else if("content-length".equals(name)) {
                try {
                    headers.ourBodyBytes = Math.min(Integer.parseInt(value), MAX_BODY_BYTES);
                } catch (final NumberFormatException ignored) {
                    headers.ourBodyBytes = 0;
                }
            }
        }
        return headers;
    }

    /**
     * @param theIn connection input
     * @param theLength bytes to read
     * @return the body text as UTF-8
     * @throws IOException on connection failures
     */
    private static String readBody(final InputStream theIn, final int theLength)
        throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int left = theLength;
        while(left > 0) {
            final int read = theIn.read(buffer, 0, Math.min(buffer.length, left));
            if(read < 0) {
                break;
            }
            bytes.write(buffer, 0, read);
            left -= read;
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * @param theTarget request target, possibly {@code /path?query}
     * @return the path without its query
     */
    private static String pathOf(final String theTarget) {
        final int split = theTarget.indexOf('?');
        return split < 0 ? theTarget : theTarget.substring(0, split);
    }

    /**
     * @param theObject object to read from
     * @param theName member name
     * @return the text value, empty when absent or not a string
     */
    private static String text(final JsonObject theObject, final String theName) {
        if(!theObject.has(theName) || !theObject.get(theName).isJsonPrimitive()) {
            return "";
        }
        return theObject.get(theName).getAsString();
    }

    /** @return 32 hex characters from a strong random source */
    private static String randomToken() {
        final byte[] seed = new byte[16];
        new SecureRandom().nextBytes(seed);
        final StringBuilder token = new StringBuilder(seed.length * 2);
        for(final byte part : seed) {
            token.append(HEX[(part >> 4) & 0x0F]).append(HEX[part & 0x0F]);
        }
        return token.toString();
    }

    /**
     * @return the chat page text with the HTTP transport in front of it, or a
     *         short notice when the page is missing from the bundle
     */
    private String renderPage() {
        final String page = readBundleText("web/chat.html");
        if(page.isEmpty()) {
            return "<!DOCTYPE html><html><body>chat.html missing from the bundle</body></html>";
        }
        final String bootstrap = bootstrapScript();
        final int anchor = page.indexOf("<head>");
        if(anchor < 0) {
            return bootstrap + page;
        }
        final int insert = anchor + "<head>".length();
        return page.substring(0, insert) + "\n" + bootstrap + page.substring(insert);
    }

    /**
     * The transport the served page runs on: the browser function is replaced
     * by an HTTP call, and the events Java pushes arrive through a long poll
     * that feeds the page's own {@code colleagueEvents} entry point.
     *
     * @return a script block to place inside the page head
     */
    private String bootstrapScript() {
        return "<script>\n"
            + "window.colleagueWeb = {token: '" + ourToken + "', host: '" + LOOPBACK_HOST
            + "', port: " + port() + "};\n"
            + "window.colleague = function (method, json) {\n"
            + "  return fetch('/call', {method: 'POST', headers: {'" + TOKEN_HEADER + "': '"
            + ourToken + "', 'content-type': 'application/json'},\n"
            + "    body: JSON.stringify({m: method, a: JSON.parse(json || '{}')})})\n"
            + "    .then(function (r) { return r.text(); });\n"
            + "};\n"
            + "(function pollLoop() {\n"
            + "  fetch('/events', {headers: {'" + TOKEN_HEADER + "': '" + ourToken + "'}})\n"
            + "    .then(function (r) { return r.text(); })\n"
            + "    .then(function (t) {\n"
            + "      if (t && window.colleagueEvents) { window.colleagueEvents(t); }\n"
            + "    })\n"
            + "    .catch(function () { })\n"
            + "    .then(function () { setTimeout(pollLoop, 120); });\n"
            + "})();\n"
            + "</script>\n";
    }

    /**
     * @param thePath bundle-relative path of the resource
     * @return its UTF-8 text, empty when it is missing or unreadable
     */
    private static String readBundleText(final String thePath) {
        final org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(WebBridge.class);
        if(bundle == null) {
            return "";
        }
        final java.net.URL entry = bundle.getEntry(thePath);
        if(entry == null) {
            return "";
        }
        try (InputStream stream = entry.openStream()) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while((read = stream.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            ColleagueLog.error("[colleague] web bridge page unreadable: " + thePath + ": "
                + ex.getMessage());
            return "";
        }
    }

    /** The two request headers this bridge acts on. */
    private static final class RequestHeaders {

        private String ourToken = "";
        private int ourBodyBytes;

        /**
         * @param theExpected token the bridge expects on data requests
         * @return whether the request carries exactly that token
         */
        boolean hasToken(final String theExpected) {
            return theExpected.equals(ourToken);
        }
    }
}
