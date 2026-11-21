package com.mad.edt.colleague.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal MCP client: JSON-RPC over a spawned stdio process or an HTTP
 * endpoint. Performs the initialize handshake, then tools/list and
 * tools/call. Kept deliberately small: no resources, no sampling, no oauth.
 */
public final class McpClient implements ToolBridge, AutoCloseable {

    /** Protocol identity this client announces in the handshake. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    /** Milliseconds one JSON-RPC answer may take before the call is cut off. */
    public static final long RESPONSE_TIMEOUT_MS = 30_000L;

    /** MCP result payload field names and the common error message prefix. */
    private static final String NEXT_CURSOR = "nextCursor";
    private static final String SERVER_PREFIX = "server ";
    private static final String INPUT_SCHEMA = "inputSchema";
    private static final String ANNOTATIONS = "annotations";
    private static final String DESCRIPTION = "description";
    private static final String CONTENT = "content";

    private final McpServerConfig config;
    private final Object channelLock = new Object();

    private Process child;
    private BufferedWriter childIn;
    private BufferedReader childOut;
    private HttpClient http;
    private boolean initialized;
    private int nextId = 1;

    /**
     * @param theConfig which server to reach and how
     */
    public McpClient(final McpServerConfig theConfig) {
        config = theConfig;
    }

    /**
     * Opens the connection and negotiates the tool catalog.
     *
     * @return discovered tools, empty when the server exposes none
     * @throws McpException when the handshake or listing fails
     */
    public List<McpToolInfo> discover() throws McpException {
        synchronized(channelLock) {
            handshake();
            final List<McpToolInfo> tools = new ArrayList<>();
            String cursor = null;
            do {
                final JsonObject params = new JsonObject();
                if(cursor != null && !cursor.isEmpty()) {
                    params.addProperty("cursor", cursor);
                }
                final JsonObject result = request("tools/list", params);
                final JsonArray listed = result.getAsJsonArray("tools");
                if(listed != null) {
                    for(final JsonElement entry : listed) {
                        final JsonObject tool = entry.getAsJsonObject();
                        tools.add(toToolInfo(tool));
                    }
                }
                cursor = result.has(NEXT_CURSOR) && !result.get(NEXT_CURSOR).isJsonNull()
                    ? result.get(NEXT_CURSOR).getAsString() : null;
            } while(cursor != null && !cursor.isEmpty());
            return List.copyOf(tools);
        }
    }

    @Override
    public List<McpToolInfo> listTools() throws McpException {
        return discover();
    }

    /**
     * Invokes one remote tool.
     *
     * @param theName tool name as discovered
     * @param theArguments JSON object of arguments
     * @return concatenated text content of the result
     * @throws McpException on transport or protocol failure
     */
    @Override
    public String call(final String theName, final JsonObject theArguments) throws McpException {
        synchronized(channelLock) {
            handshake();
            final JsonObject params = new JsonObject();
            params.addProperty("name", theName);
            params.add("arguments", theArguments == null ? new JsonObject() : theArguments);
            final JsonObject result = request("tools/call", params);
            if(result.has("isError") && result.get("isError").getAsBoolean()) {
                throw new McpException("tool " + theName + " reported an error: "
                    + joinText(result));
            }
            return joinText(result);
        }
    }

    /** Closes the child process or the HTTP client, whichever is open. */
    @Override
    public void close() {
        synchronized(channelLock) {
            initialized = false;
            if(child != null) {
                child.destroy();
                child = null;
                childIn = null;
                childOut = null;
            }
        }
    }

    /**
     * Runs the initialize handshake once per connection: request, then the
     * initialized notification the protocol requires before any other call.
     *
     * @throws McpException when the server refuses the handshake
     */
    private void handshake() throws McpException {
        if(initialized) {
            return;
        }
        final JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "colleague");
        clientInfo.addProperty("version", "1.0");
        final JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        params.add("clientInfo", clientInfo);
        request("initialize", params);
        sendNotification("notifications/initialized");
        initialized = true;
    }

    /**
     * Sends one JSON-RPC request and waits for its answer.
     *
     * @param theMethod protocol method name
     * @param theParams parameters object, may be empty
     * @return the result object of the answer
     * @throws McpException on transport failure or an error answer
     */
    private JsonObject request(final String theMethod, final JsonObject theParams)
        throws McpException {
        final JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", Integer.valueOf(nextId++));
        message.addProperty("method", theMethod);
        message.add("params", theParams == null ? new JsonObject() : theParams);
        final JsonObject answer = config.isHttp()
            ? roundTripHttp(message) : roundTripStdio(message);
        if(answer.has("error")) {
            final JsonElement error = answer.get("error");
            final String text = error.isJsonObject() && error.getAsJsonObject().has("message")
                ? error.getAsJsonObject().get("message").getAsString() : error.toString();
            throw new McpException(SERVER_PREFIX + config.getName() + " refused " + theMethod
                + ": " + text);
        }
        final JsonElement result = answer.get("result");
        return result != null && result.isJsonObject() ? result.getAsJsonObject()
            : new JsonObject();
    }

    /**
     * Sends one notification, no answer expected.
     *
     * @param theMethod notification method name
     * @throws McpException when the channel is broken
     */
    private void sendNotification(final String theMethod) throws McpException {
        final JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", theMethod);
        message.add("params", new JsonObject());
        if(config.isHttp()) {
            postHttp(message);
        } else {
            writeLine(message.toString());
        }
    }

    /**
     * One HTTP round-trip: POST the request, parse the JSON answer.
     *
     * @param theMessage request object to send
     * @return parsed answer
     * @throws McpException when the endpoint is unreachable or answers badly
     */
    private JsonObject roundTripHttp(final JsonObject theMessage) throws McpException {
        final String body = postHttp(theMessage);
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (final RuntimeException ex) {
            throw new McpException(SERVER_PREFIX + config.getName()
                + " answered with a non-JSON body", ex);
        }
    }

    /**
     * @param theMessage request object to send
     * @return raw response body
     * @throws McpException when the endpoint is unreachable or refuses
     */
    private String postHttp(final JsonObject theMessage) throws McpException {
        if(http == null) {
            http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(RESPONSE_TIMEOUT_MS))
                .build();
        }
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(config.getUrl()))
                .timeout(Duration.ofMillis(RESPONSE_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(theMessage.toString(),
                    StandardCharsets.UTF_8))
                .build();
            final HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() / 100 != 2) {
                throw new McpException(SERVER_PREFIX + config.getName() + " answered HTTP "
                    + Integer.valueOf(response.statusCode()));
            }
            return response.body();
        } catch (final IOException ex) {
            throw new McpException("cannot reach " + config.getUrl(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpException("call interrupted while waiting for "
                + config.getName(), ex);
        }
    }

    /**
     * One stdio round-trip: write the request as one line, read lines until
     * the answer with the matching id arrives.
     *
     * @param theMessage request object to send
     * @return parsed answer
     * @throws McpException when the process died or answered badly
     */
    private JsonObject roundTripStdio(final JsonObject theMessage) throws McpException {
        ensureChild();
        final String id = theMessage.get("id").getAsString();
        writeLine(theMessage.toString());
        try {
            final long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS;
            String line;
            while((line = childOut.readLine()) != null) {
                if(System.currentTimeMillis() > deadline) {
                    throw new McpException(SERVER_PREFIX + config.getName() + " did not answer "
                        + "within " + Long.valueOf(RESPONSE_TIMEOUT_MS) + " ms");
                }
                if(line.isBlank()) {
                    continue;
                }
                final JsonObject answer = JsonParser.parseString(line).getAsJsonObject();
                if(answer.has("id") && id.equals(answer.get("id").getAsString())) {
                    return answer;
                }
            }
            throw new McpException(SERVER_PREFIX + config.getName() + " closed the pipe");
        } catch (final IOException ex) {
            throw new McpException("stdio round-trip to " + config.getName() + " failed", ex);
        } catch (final IllegalStateException ex) {
            throw new McpException(SERVER_PREFIX + config.getName() + " sent a non-JSON line", ex);
        } finally {
            if(child != null && !child.isAlive()) {
                initialized = false;
            }
        }
    }

    /**
     * @throws McpException when the child process cannot be spawned
     */
    private void ensureChild() throws McpException {
        if(child != null && child.isAlive()) {
            return;
        }
        try {
            child = new ProcessBuilder(config.getCommand())
                .redirectErrorStream(false).start();
            childIn = new BufferedWriter(new OutputStreamWriter(
                child.getOutputStream(), StandardCharsets.UTF_8));
            childOut = new BufferedReader(new InputStreamReader(
                child.getInputStream(), StandardCharsets.UTF_8));
            initialized = false;
        } catch (final IOException ex) {
            throw new McpException("cannot spawn " + String.join(" ",
                config.getCommand()), ex);
        }
    }

    /**
     * @param theLine one JSON-RPC message
     * @throws McpException when the pipe to the child is broken
     */
    private void writeLine(final String theLine) throws McpException {
        try {
            childIn.write(theLine);
            childIn.write('\n');
            childIn.flush();
        } catch (final IOException ex) {
            initialized = false;
            throw new McpException("cannot write to " + config.getName(), ex);
        }
    }

    /**
     * @param theTool one entry of the tools/list result
     * @return parsed tool info, read-only only when the server said so
     */
    private static McpToolInfo toToolInfo(final JsonObject theTool) {
        final JsonObject schema = theTool.has(INPUT_SCHEMA)
            && theTool.get(INPUT_SCHEMA).isJsonObject()
            ? theTool.getAsJsonObject(INPUT_SCHEMA) : null;
        boolean readOnly = false;
        if(theTool.has(ANNOTATIONS) && theTool.get(ANNOTATIONS).isJsonObject()) {
            final JsonObject annotations = theTool.getAsJsonObject(ANNOTATIONS);
            readOnly = annotations.has("readOnlyHint")
                && annotations.get("readOnlyHint").getAsBoolean();
        }
        return new McpToolInfo(theTool.has("name") ? theTool.get("name").getAsString() : "",
            theTool.has(DESCRIPTION) && !theTool.get(DESCRIPTION).isJsonNull()
                ? theTool.get(DESCRIPTION).getAsString() : "",
            schema, readOnly);
    }

    /**
     * @param theResult a tools/call result object
     * @return all text content parts concatenated
     */
    private static String joinText(final JsonObject theResult) {
        final StringBuilder text = new StringBuilder();
        final JsonArray content = theResult.has(CONTENT) && theResult.get(CONTENT).isJsonArray()
            ? theResult.getAsJsonArray(CONTENT) : new JsonArray();
        for(final JsonElement part : content) {
            if(part.isJsonObject() && part.getAsJsonObject().has("text")) {
                if(text.length() > 0) {
                    text.append('\n');
                }
                text.append(part.getAsJsonObject().get("text").getAsString());
            }
        }
        return text.toString();
    }
}
