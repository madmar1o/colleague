package com.mad.edt.colleague.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.agent.IToolExecutor;

/**
 * A discovered MCP tool joins the agent catalog under a server-prefixed name
 * so it can never collide with a built-in one, and a tool that does not
 * announce itself read-only is treated as a writer: the user has to confirm
 * the call before the client sends it.
 */
class McpToolAdapterTest {

    /** scripted bridge standing in for a live client */
    private static final class FakeBridge implements ToolBridge {

        final List<String> called = new ArrayList<>();
        String answer = "remote said ok";

        @Override
        public List<McpToolInfo> listTools() {
            return List.of();
        }

        @Override
        public String call(final String theName, final JsonObject theArguments) {
            called.add(theName + "|" + (theArguments == null ? "{}" : theArguments.toString()));
            return answer;
        }
    }

    private static McpToolInfo tool(final String theName, final boolean theReadOnly) {
        return new McpToolInfo(theName, "tool " + theName, new JsonObject(), theReadOnly);
    }

    @Test
    @DisplayName("the advertised name carries the server prefix, the remote name stays inside")
    void namesArePrefixed() {
        final FakeBridge bridge = new FakeBridge();
        final McpToolAdapter adapter = new McpToolAdapter("qdb", bridge,
            tool("query", true), name -> name);

        Assertions.assertEquals("mcp_qdb_query", adapter.getName());
        Assertions.assertEquals("mcp_qdb_query", adapter.getSpec().getName(),
            "the spec name must match the executor name or the loop cannot dispatch");
        Assertions.assertTrue(adapter.isParallelSafe(),
            "a readOnlyHint tool may run side by side like a reader");

        adapter.execute("{\"sql\":\"select 1\"}");
        Assertions.assertEquals(List.of("query|{\"sql\":\"select 1\"}"), bridge.called,
            "the call must carry the remote name, not the prefixed one");
    }

    @Test
    @DisplayName("a tool without a read-only hint needs the user confirmation first")
    void writeLikeToolNeedsConfirmation() {
        final FakeBridge bridge = new FakeBridge();
        final List<String> asked = new ArrayList<>();
        final McpToolAdapter adapter = new McpToolAdapter("qdb", bridge,
            tool("drop_row", false), question -> {
                asked.add(question);
                return "denied";
            });

        Assertions.assertFalse(adapter.isParallelSafe(),
            "no annotation means write-like: serial and confirmation-guarded");
        final String result = adapter.execute("{\"row\":\"42\"}");
        Assertions.assertTrue(result.startsWith("ERROR"), result);
        Assertions.assertEquals(1, asked.size());
        Assertions.assertTrue(asked.get(0).contains("drop_row"), asked.toString());
        Assertions.assertTrue(bridge.called.isEmpty(),
            "a denied confirmation must not reach the server at all");
    }

    @Test
    @DisplayName("a confirmed write-like tool runs and its text comes back")
    void confirmedWriteToolRuns() {
        final FakeBridge bridge = new FakeBridge();
        final McpToolAdapter adapter = new McpToolAdapter("qdb", bridge,
            tool("drop_row", false), question -> "yes");

        Assertions.assertEquals("remote said ok", adapter.execute("{}"));
        Assertions.assertEquals(List.of("drop_row|{}"), bridge.called);
    }

    @Test
    @DisplayName("the catalog prefixes names and never shadows a built-in tool")
    void catalogKeepsBuiltinsOnCollision() {
        final FakeBridge bridge = new FakeBridge();
        final McpServerConfig config = new McpServerConfig("qdb", null, "http://localhost", true);
        final McpCatalog catalog = new McpCatalog(
            unusedConfig -> new McpToolInfo[] {
                new McpToolInfo("read_file", "collides with the built-in", new JsonObject(), true),
                new McpToolInfo("query", "unique name", new JsonObject(), true),
            },
            unusedConfig -> bridge,
            () -> List.of(config));

        final List<IToolExecutor> tools = catalog.tools(
            Set.of("read_file"), question -> "yes");
        final List<String> names = tools.stream().map(IToolExecutor::getName).toList();

        Assertions.assertTrue(names.contains("mcp_qdb_query"), names.toString());
        Assertions.assertFalse(names.contains("mcp_qdb_read_file"),
            "a colliding remote name must not be offered: " + names);
        Assertions.assertFalse(names.contains("read_file"),
            "the catalog contributes no bare names: " + names);
    }

    @Test
    @DisplayName("a disabled server contributes nothing and is never contacted")
    void disabledServersStaySilent() {
        final FakeBridge bridge = new FakeBridge();
        final McpServerConfig config = new McpServerConfig("qdb", null, "http://localhost", false);
        final McpCatalog catalog = new McpCatalog(
            unusedConfig -> {
                throw new IllegalStateException("a disabled server must not be discovered");
            },
            unusedConfig -> bridge,
            () -> List.of(config));

        Assertions.assertTrue(catalog.tools(Set.of(), question -> "yes").isEmpty());
        Assertions.assertTrue(bridge.called.isEmpty());
    }
}
