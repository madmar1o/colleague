package com.mad.edt.colleague.ui.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * The debug web bridge is the same page protocol over HTTP: a browser tab can
 * drive and watch a chat panel while the plugin process keeps owning the model
 * runtime. What must hold is the transport contract, not the browser: loopback
 * only, a token on every request, the page arriving with its bootstrap wired to
 * that token, page calls landing in the panel handler, and pushed events
 * reaching the long poll. No SWT widget and no embedded browser is involved.
 */
class WebBridgeTest {

    private static final String PATH_CALL = "/call";
    private static final String PATH_EVENTS = "/events";

    private WebBridge ourBridge;

    /** Calls the page made, in arrival order. */
    private final List<String[]> ourPageCalls = new ArrayList<>();

    @BeforeEach
    void startsOnAnEphemeralLoopbackPort() throws IOException {
        ourPageCalls.clear();
        ourBridge = WebBridge.start(0);
        ourBridge.setPageCallSink((method, arguments) -> ourPageCalls
            .add(new String[] {method, arguments == null ? "" : arguments.toString()}));
    }

    @AfterEach
    void closesTheSocket() {
        if(ourBridge != null) {
            ourBridge.close();
        }
    }

    @Test
    @DisplayName("the bridge stays off unless a port was asked for")
    void offByDefault() {
        Assertions.assertEquals(0, WebBridge.requestedPort(),
            "no flag means no listening socket at all");
    }

    @Test
    @DisplayName("the socket is loopback and carries an unguessable token")
    void loopbackWithAToken() {
        Assertions.assertTrue(WebBridge.isLoopback(ourBridge.host()),
            "the debug page must never be reachable from another machine");
        Assertions.assertTrue(ourBridge.token().length() >= 32,
            "the token has to resist guessing, got: " + ourBridge.token());
        Assertions.assertNotEquals(0, ourBridge.port());
    }

    @Test
    @DisplayName("the served page carries the transport bootstrap, not the token leak")
    void servedPageCarriesTheBootstrap() throws IOException {
        final String html = read(get("/"));

        Assertions.assertTrue(html.contains("colleagueEvents"),
            "the page still delivers events through its own handler");
        Assertions.assertTrue(html.contains(PATH_CALL),
            "the bootstrap sends page calls over HTTP");
        Assertions.assertTrue(html.contains(PATH_EVENTS),
            "the bootstrap collects pushed events by polling");
        Assertions.assertTrue(html.contains(ourBridge.token()),
            "the bootstrap has to know the token to reach the panel");
    }

    @Test
    @DisplayName("a call without the token never reaches the panel")
    void callWithoutTokenIsRefused() throws IOException {
        final HttpURLConnection reply = post(PATH_CALL, "{\"m\":\"send\",\"a\":{\"text\":\"hi\"}}",
            "wrong-token");

        Assertions.assertEquals(401, reply.getResponseCode());
        Assertions.assertTrue(ourPageCalls.isEmpty(),
            "an unauthenticated request must not drive the chat");
    }

    @Test
    @DisplayName("a page call arrives in the panel handler as method plus arguments")
    void pageCallReachesThePanel() throws IOException {
        final HttpURLConnection reply = post(PATH_CALL,
            "{\"m\":\"send\",\"a\":{\"text\":\"\\u043f\\u0440\\u0438\\u0432\\u0435\\u0442\"}}",
            ourBridge.token());

        Assertions.assertEquals(200, reply.getResponseCode());
        Assertions.assertEquals(1, ourPageCalls.size());
        Assertions.assertEquals("send", ourPageCalls.get(0)[0]);
        Assertions.assertTrue(ourPageCalls.get(0)[1].contains("привет"),
            "the argument text must survive the wire, got: " + ourPageCalls.get(0)[1]);
    }

    @Test
    @DisplayName("pushed events reach the page through the long poll")
    void pushedEventsReachThePoll() throws IOException {
        final JsonObject payload = new JsonObject();
        payload.addProperty("text", "ответ модели");
        ourBridge.push("answer", payload);

        final String batch = read(get(PATH_EVENTS));

        Assertions.assertTrue(batch.contains("\"answer\""),
            "the event kind must travel, got: " + batch);
        Assertions.assertTrue(batch.contains("ответ модели"),
            "the payload must travel as it was pushed, got: " + batch);
        // the queue is drained once: a second poll sees nothing left
        Assertions.assertEquals("[]", read(get(PATH_EVENTS)));
    }

    @Test
    @DisplayName("an empty poll waits briefly and answers with an empty batch")
    void emptyPollStaysCheap() throws IOException {
        final long started = System.currentTimeMillis();
        final String batch = read(get(PATH_EVENTS));
        final long waited = System.currentTimeMillis() - started;

        Assertions.assertEquals("[]", batch);
        Assertions.assertTrue(waited >= 50L,
            "an empty poll must hold the request instead of spinning the client, waited "
                + waited + "ms");
        Assertions.assertTrue(waited < 5_000L, "a poll must not hold forever: " + waited + "ms");
    }

    @Test
    @DisplayName("a closed bridge stops answering")
    void closedBridgeStopsServing() throws IOException {
        final URL url = new URL("http://" + ourBridge.host() + ":" + ourBridge.port() + "/");
        ourBridge.close();
        ourBridge = null;

        final HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(2_000);
        Assertions.assertThrows(IOException.class, conn::getInputStream,
            "the socket has to be gone after the bridge closes");
    }

    /**
     * @param thePath request path below the bridge root
     * @return an open connection for a GET
     * @throws IOException on connection failures
     */
    private HttpURLConnection get(final String thePath) throws IOException {
        final HttpURLConnection conn = open(thePath);
        conn.setRequestProperty("x-colleague-token", ourBridge.token());
        conn.setRequestMethod("GET");
        return conn;
    }

    /**
     * @param thePath request path below the bridge root
     * @param theBody JSON text of the call
     * @param theToken token header value to send
     * @return an open connection with the request already written
     * @throws IOException on connection failures
     */
    private HttpURLConnection post(final String thePath, final String theBody,
        final String theToken) throws IOException {
        final HttpURLConnection conn = open(thePath);
        conn.setRequestProperty("x-colleague-token", theToken);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (OutputStream stream = conn.getOutputStream()) {
            stream.write(theBody.getBytes(StandardCharsets.UTF_8));
        }
        conn.getResponseCode();
        return conn;
    }

    /**
     * @param thePath request path below the bridge root
     * @return a connection to the running bridge
     * @throws IOException when the bridge is no longer listening
     */
    private HttpURLConnection open(final String thePath) throws IOException {
        final URL url = new URL("http://" + ourBridge.host() + ":" + ourBridge.port() + thePath);
        final HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(8_000);
        return conn;
    }

    /**
     * @param theConnection connection to read the body of
     * @return the body text as UTF-8
     * @throws IOException on read failures
     */
    private static String read(final HttpURLConnection theConnection) throws IOException {
        final InputStream stream = theConnection.getResponseCode() >= 400
            ? theConnection.getErrorStream() : theConnection.getInputStream();
        if(stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
