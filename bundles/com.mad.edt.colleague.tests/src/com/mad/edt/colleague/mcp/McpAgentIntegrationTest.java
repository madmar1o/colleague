package com.mad.edt.colleague.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ToolCall;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Acceptance proof for the whole path: a real spawned tool server, the real
 * catalog and adapters, and the real agent loop. The model (scripted here)
 * only ever sees and calls the prefixed remote name, the server's answer
 * travels back as the tool result, and a built-in name still blocks the
 * remote tool with the same name even when the server is alive.
 */
class McpAgentIntegrationTest {

    /**
     * Round-trips the scripted list and remembers every request, so the test
     * can read what the loop advertised and what tool results reached the
     * next round.
     */
    private static final class ScriptedProvider implements ILLMProvider {

        final List<ChatRequest> requests = new ArrayList<>();
        private final List<ChatResult> script;
        private int next;

        ScriptedProvider(final List<ChatResult> theScript) {
            script = theScript;
        }

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public ProviderConfig getConfig() {
            return null;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) {
            requests.add(theRequest);
            return script.get(next++);
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            return chat(theRequest);
        }

        @Override
        public List<String> listModels() {
            return List.of("stub");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** The built-in reader the remote server tries to collide with. */
    private static final class LocalReader implements IToolExecutor {

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("echo", "local echo", null);
        }

        @Override
        public String execute(final String theArgumentsJson) {
            return "local answer";
        }
    }

    /**
     * @param theDir temp folder to write the fake server source into
     * @return a config that spawns the live stdio fake
     * @throws Exception when the source file cannot be written
     */
    private static McpServerConfig spawnFake(final Path theDir) throws Exception {
        final Path source = theDir.resolve("StdioFakeServer.java");
        Files.writeString(source, McpClientHandshakeTest.STDIO_FAKE_SOURCE,
            StandardCharsets.UTF_8);
        final String javaBin = System.getProperty("java.home")
            + java.io.File.separatorChar + "bin" + java.io.File.separatorChar + "java";
        return new McpServerConfig("stdio",
            List.of(javaBin, source.toAbsolutePath().toString()), null, true);
    }

    private static ChatResult toolRound(final String theName, final String theArgs) {
        return new ChatResult("", null, "stub", "tool_calls", 1, 1,
            List.of(new ToolCall("t1", theName, theArgs)));
    }

    private static ChatResult finalRound(final String theText) {
        return new ChatResult(theText, null, "stub", "stop", 1, 1, List.of());
    }

    @Test
    @DisplayName("a spawned stdio server feeds its tools through the real agent loop")
    void liveServerFeedsTheAgent(@TempDir final Path theDir) throws Exception {
        final McpServerConfig config = spawnFake(theDir);
        final McpClient client = new McpClient(config);
        try {
            final McpCatalog catalog = new McpCatalog(
                unusedConfig -> client.discover().toArray(new McpToolInfo[0]),
                unusedConfig -> client,
                () -> List.of(config));
            final List<IToolExecutor> remote =
                catalog.tools(Set.of("read_file"), question -> "yes");
            Assertions.assertEquals(List.of("mcp_stdio_echo"),
                remote.stream().map(IToolExecutor::getName).toList());

            final List<IToolExecutor> tools = new ArrayList<>(remote);
            final ScriptedProvider provider = new ScriptedProvider(List.of(
                toolRound("mcp_stdio_echo", "{}"),
                finalRound("the server answered")));
            final AgentAnswer answer = new AgentLoop(provider, tools, "stub", 5,
                new CancellationFlag()).run("sys", "call the remote echo");

            Assertions.assertEquals("the server answered", answer.getText());
            final List<String> advertised =
                provider.requests.get(0).getTools().stream().map(ToolSpec::getName).toList();
            Assertions.assertEquals(List.of("mcp_stdio_echo"), advertised);
            final String toolResult = provider.requests.get(1).getMessages().stream()
                .map(ChatMessage::getContent)
                .filter(text -> text != null && text.contains("stdio echo ok"))
                .findFirst().orElse("");
            Assertions.assertTrue(toolResult.contains("stdio echo ok"),
                "the server's answer must reach the next model round: "
                    + provider.requests.get(1).getMessages());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("a built-in name blocks the live remote tool of the same name")
    void builtInWinsEvenAgainstALiveServer(@TempDir final Path theDir) throws Exception {
        final McpServerConfig config = spawnFake(theDir);
        final McpClient client = new McpClient(config);
        try {
            final McpCatalog catalog = new McpCatalog(
                unusedConfig -> client.discover().toArray(new McpToolInfo[0]),
                unusedConfig -> client,
                () -> List.of(config));
            final List<IToolExecutor> tools = new ArrayList<>();
            tools.add(new LocalReader());
            tools.addAll(catalog.tools(
                tools.stream().map(IToolExecutor::getName).collect(java.util.stream.Collectors.toSet()),
                question -> "yes"));

            final List<String> advertised =
                tools.stream().map(IToolExecutor::getName).toList();
            Assertions.assertEquals(List.of("echo"), advertised,
                "the live server's echo loses to the built-in reader");
        } finally {
            client.close();
        }
    }
}
