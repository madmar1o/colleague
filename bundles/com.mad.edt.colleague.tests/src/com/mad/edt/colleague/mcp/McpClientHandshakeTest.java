package com.mad.edt.colleague.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client speaks JSON-RPC to two kinds of tool server: an HTTP endpoint
 * and a spawned stdio process. Both fakes here record the request order, so
 * one test proves the initialize handshake precedes tools/list, and the
 * tools/call result text reaches the caller unchanged on either transport.
 */
class McpClientHandshakeTest {

    /** The JSON-RPC identity of the tool both fakes expose. */
    private static final String TOOL_NAME = "echo";

    /** JSON-RPC methods the client is expected to drive, in handshake order. */
    private static final String INITIALIZE = "initialize";
    private static final String TOOLS_LIST = "tools/list";
    private static final String TOOLS_CALL = "tools/call";

    /**
     * Minimal in-process HTTP endpoint on a plain server socket answering
     * the three JSON-RPC methods the client is allowed to use; notifications
     * are answered but not recorded, so the method list shows only real
     * requests.
     */
    private static final class FakeHttpServer implements AutoCloseable {

        final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        private final ServerSocket socket;
        private final AtomicBoolean running = new AtomicBoolean(true);

        FakeHttpServer() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final Thread acceptor = new Thread(this::acceptLoop, "mcp-fake-http");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /** @return base URL the client should be pointed at */
        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/mcp";
        }

        private void acceptLoop() {
            while(running.get()) {
                final Socket client;
                try {
                    client = socket.accept();
                } catch (final IOException ex) {
                    return; // the close() unblocked accept
                }
                // one connection must not be able to park the whole server:
                // a client that opens a connection and stays silent would
                // otherwise block every later request until its own timeout
                final Thread worker = new Thread(() -> {
                    try (Socket connected = client) {
                        serve(connected);
                    } catch (final IOException ex) {
                        // a client that vanished mid-request is not worth failing over
                    }
                }, "mcp-fake-http-conn");
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void serve(final Socket theClient) throws IOException {
            theClient.setSoTimeout(10_000);
            final InputStream in = theClient.getInputStream();
            // the head is taken one byte at a time on purpose: a buffered reader
            // would already have pulled the body that follows the empty line into
            // its own buffer, and the body read below would then wait for bytes
            // that arrived long ago until the socket timeout closes the connection
            final StringBuilder head = new StringBuilder();
            int current;
            while((current = in.read()) >= 0) {
                head.append((char) current);
                if(head.length() >= 4
                    && head.charAt(head.length() - 4) == '\r'
                    && head.charAt(head.length() - 3) == '\n'
                    && head.charAt(head.length() - 2) == '\r'
                    && head.charAt(head.length() - 1) == '\n') {
                    break;
                }
            }
            int contentLength = 0;
            for(final String line : head.toString().split("\r\n")) {
                if(line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            final byte[] bodyBytes = new byte[contentLength];
            in.readNBytes(bodyBytes, 0, contentLength);
            final String request = new String(bodyBytes, StandardCharsets.UTF_8);
            final String method = between(request, "\"method\":\"", "\"");
            final String id = between(request, "\"id\":", ",");
            if(!id.isEmpty()) {
                methods.add(method);
            }
            final String result;
            if(INITIALIZE.equals(method)) {
                result = "{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\"}}";
            } else if(TOOLS_LIST.equals(method)) {
                result = "{\"tools\":[{\"name\":\"" + TOOL_NAME + "\","
                    + "\"description\":\"echoes its argument\","
                    + "\"inputSchema\":{\"type\":\"object\"},"
                    + "\"annotations\":{\"readOnlyHint\":true}}]}";
            } else if(TOOLS_CALL.equals(method) && !request.contains("unknown_tool")) {
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"echo: hello\"}],"
                    + "\"isError\":false}";
            } else {
                result = null;
            }
            final String body;
            if(result == null) {
                body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}";
            } else {
                body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
            }
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            final OutputStream out = theClient.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        }

        /**
         * @param theText full request line
         * @param theStart opening marker of the wanted value
         * @param theEnd closing marker of the wanted value
         * @return the text between the markers, empty when either is missing
         */
        private static String between(final String theText, final String theStart,
            final String theEnd) {
            final int from = theText.indexOf(theStart);
            if(from < 0) {
                return "";
            }
            final int start = from + theStart.length();
            final int end = theText.indexOf(theEnd, start);
            return end < 0 ? "" : theText.substring(start, end);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                socket.close();
            } catch (final IOException ex) {
                // already gone
            }
        }
    }

    /**
     * Stand-in tool server on stdin/stdout, written out as a single-file
     * source and launched straight by the JDK: the same fake protocol as the
     * HTTP one, line-delimited. Source launch needs no classpath, which is
     * what keeps the fake working inside the OSGi test runtime. Shared with
     * {@code McpAgentIntegrationTest}, which drives it through a real agent.
     */
    static final String STDIO_FAKE_SOURCE = """
        import java.io.BufferedReader;
        import java.io.BufferedWriter;
        import java.io.InputStreamReader;
        import java.io.OutputStreamWriter;
        import java.io.Writer;
        import java.nio.charset.StandardCharsets;

        public class StdioFakeServer {
            public static void main(String[] args) throws Exception {
                BufferedReader lines = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
                Writer out = new BufferedWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
                String line;
                while((line = lines.readLine()) != null) {
                    if(line.isBlank()) {
                        continue;
                    }
                    String method = between(line, q("method") + c(58) + c(34), c(34));
                    String id = between(line, q("id") + c(58), c(44));
                    String result;
                    if("initialize".equals(method)) {
                        result = "{" + q("protocolVersion") + c(58) + q("2024-11-05") + ","
                            + q("capabilities") + c(58) + "{},"
                            + q("serverInfo") + c(58) + "{" + q("name") + c(58) + q("stdio-fake") + "}}";
                    } else if("tools/list".equals(method)) {
                        result = "{" + q("tools") + c(58) + "[{" + q("name") + c(58) + q("echo") + ","
                            + q("description") + c(58) + q("stdio echo") + ","
                            + q("inputSchema") + c(58) + "{" + q("type") + c(58) + q("object") + "}}]}";
                    } else if("tools/call".equals(method)) {
                        result = "{" + q("content") + c(58) + "[{" + q("type") + c(58) + q("text") + ","
                            + q("text") + c(58) + q("stdio echo ok") + "}],"
                            + q("isError") + c(58) + "false}";
                    } else if("notifications/initialized".equals(method)) {
                        continue;
                    } else {
                        writeLine(out, response(id)
                            + q("error") + c(58) + "{" + q("code") + c(58) + "-32601,"
                            + q("message") + c(58) + q("unknown method") + "}}");
                        continue;
                    }
                    writeLine(out, response(id) + q("result") + c(58) + result + "}");
                }
                out.flush();
            }

            private static String q(String value) {
                return (char) 34 + value + (char) 34;
            }

            private static String c(int ch) {
                return "" + (char) ch;
            }

            private static String response(String id) {
                return "{" + q("jsonrpc") + c(58) + q("2.0") + "," + q("id") + c(58) + id + ",";
            }

            private static String between(String text, String start, String end) {
                int from = text.indexOf(start);
                if(from < 0) {
                    return "";
                }
                int s = from + start.length();
                int e = text.indexOf(end, s);
                return e < 0 ? "" : text.substring(s, e);
            }

            private static void writeLine(Writer out, String line) throws Exception {
                out.write(line);
                out.write(System.lineSeparator());
                out.flush();
            }
        }
        """;

    @Test
    @DisplayName("the HTTP handshake initializes the server and lists its tools")
    void handshakeListsTools() throws Exception {
        try (FakeHttpServer fake = new FakeHttpServer();
            McpClient client = new McpClient(
                new McpServerConfig("srv", null, fake.url(), true))) {

            final List<McpToolInfo> tools = client.discover();

            Assertions.assertEquals(1, tools.size());
            Assertions.assertEquals(TOOL_NAME, tools.get(0).name());
            Assertions.assertEquals("echoes its argument", tools.get(0).description());
            Assertions.assertTrue(tools.get(0).readOnly(),
                "the readOnlyHint annotation must survive into the tool info");
            Assertions.assertEquals(List.of(INITIALIZE, TOOLS_LIST), fake.methods,
                "initialize must come before listing, nothing else");

            final String text = client.call(TOOL_NAME, null);
            Assertions.assertEquals("echo: hello", text);
            Assertions.assertEquals(List.of(INITIALIZE, TOOLS_LIST, TOOLS_CALL),
                fake.methods, "the call must not redo the handshake");
        }
    }

    @Test
    @DisplayName("the stdio transport drives a spawned server through the same handshake")
    void stdioHandshakeAndCall(@TempDir final Path theDir) throws Exception {
        final Path source = theDir.resolve("StdioFakeServer.java");
        Files.writeString(source, STDIO_FAKE_SOURCE, StandardCharsets.UTF_8);
        final String javaBin = System.getProperty("java.home")
            + java.io.File.separatorChar + "bin" + java.io.File.separatorChar + "java";
        final McpServerConfig config = new McpServerConfig("stdio",
            List.of(javaBin, source.toAbsolutePath().toString()), null, true);
        try (McpClient client = new McpClient(config)) {
            final List<McpToolInfo> tools = client.discover();
            Assertions.assertEquals(1, tools.size());
            Assertions.assertEquals(TOOL_NAME, tools.get(0).name());
            Assertions.assertFalse(tools.get(0).readOnly(),
                "a tool without annotations is write-like by default");

            Assertions.assertEquals("stdio echo ok", client.call(TOOL_NAME, null));
        }
    }

    @Test
    @DisplayName("a server answering with an error object fails the call, not the run")
    void toolCallErrorBecomesException() throws Exception {
        try (FakeHttpServer fake = new FakeHttpServer();
            McpClient client = new McpClient(
                new McpServerConfig("srv", null, fake.url(), true))) {
            client.discover();

            final McpException failure = Assertions.assertThrows(McpException.class,
                () -> client.call("unknown_tool", null));
            Assertions.assertTrue(fake.methods.contains(TOOLS_CALL));
            Assertions.assertTrue(failure.getMessage().contains("unknown method"),
                failure.getMessage());
        }
    }
}
