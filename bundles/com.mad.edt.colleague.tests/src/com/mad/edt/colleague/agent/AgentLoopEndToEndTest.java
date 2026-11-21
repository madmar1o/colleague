package com.mad.edt.colleague.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.subagent.SubagentTool;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.OllamaLLMProvider;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Unattended end-to-end proof of the agent over a real local model: the loop,
 * the real provider transport and real file tools run one complete task on a
 * throwaway workspace copy, and the harness asserts only through the observer
 * contract plus the resulting file state - no human reads logs to decide
 * whether the run was good.
 *
 * <p>Deliberate opt-in: the suite sets no live endpoint as a build
 * requirement, so this harness engages only when the environment explicitly
 * asks for it ({@code COLLEAGUE_E2E=1}) and the endpoint really answers;
 * otherwise it skips instead of turning a missing model server into a build
 * failure.</p>
 */
@EnabledIfEnvironmentVariable(named = "COLLEAGUE_E2E", matches = "1")
class AgentLoopEndToEndTest {

    private static final String SERVER_URL =
        System.getenv().getOrDefault("COLLEAGUE_E2E_URL", "http://127.0.0.1:11434");
    /*
     * The default benchmark model is the one this harness's budget was
     * measured against: a small non-thinking instruct model answers the
     * fixed scenario within the two-minute wall clock on the harness GPU.
     * A thinking model (e.g. qwen3:4b) samples thinking chains between
     * 60 and 200 seconds for the very same scenario on the same card, so
     * the wall-clock threshold would report hardware jitter as agent
     * degradation. The environment may still point the harness at any
     * capable model via COLLEAGUE_E2E_MODEL.
     */
    private static final String MODEL =
        System.getenv().getOrDefault("COLLEAGUE_E2E_MODEL", "dcostenco/prism-coder:2b");

    /** The wrong identifier planted in the fixture; the task fixes it. */
    private static final String BROKEN_NAME = "BrokenHelper";
    /** The identifier the task asks to write instead. */
    private static final String FIXED_NAME = "CalculateTotal";

    // fixture tool names and the secret word repeated across the scenarios
    private static final String READ_FILE = "read_file";
    private static final String EDIT_FILE = "edit_file";
    private static final String UMBRELLA = "umbrella";

    /**
     * Records the trace in call order and passes every event through to the
     * production log observer, so one run both asserts its behaviour and
     * proves the auditable console lines of that observer.
     */
    private static final class RecordingObserver implements AgentObserver {

        static final String TOOL_EVENT = "tool ";

        final List<String> events = new ArrayList<>();
        private final LoggingAgentObserver logs = new LoggingAgentObserver();

        @Override
        public void onRound(final int theStep, final int theRequestedToolCalls) {
            events.add("round " + theStep + " requested=" + theRequestedToolCalls);
            logs.onRound(theStep, theRequestedToolCalls);
        }

        @Override
        public void onToolCall(final int theStep, final String theName, final String theArgumentsJson,
            final String theResult, final long theElapsedMs) {
            events.add("step " + theStep + " " + TOOL_EVENT + theName
                + " result=" + theResult);
            logs.onToolCall(theStep, theName, theArgumentsJson, theResult, theElapsedMs);
        }

        /**
         * @param theName tool to look for
         * @return {@code true} when the trace executed this tool
         */
        boolean usedTool(final String theName) {
            return events.stream().anyMatch(event -> event.contains(TOOL_EVENT + theName));
        }

        /**
         * @return name of the tool of the first recorded call, {@code ""} when
         *         the run executed none
         */
        String firstTool() {
            for(final String event : events) {
                final int toolAt = event.indexOf(TOOL_EVENT);
                if(toolAt >= 0) {
                    return event.substring(toolAt + TOOL_EVENT.length()).split(" ")[0];
                }
            }
            return "";
        }
    }

    /** Shared workspace-root safety of the plain-file tool stand-ins. */
    private abstract static class FileTool implements IToolExecutor {

        final Path root;

        FileTool(final Path theRoot) {
            root = theRoot;
        }

        /**
         * @param theArgumentsJson tool arguments from the model
         * @param theProperty argument naming the file
         * @return resolved file inside the workspace
         * @throws IOException when the path escapes the workspace or is absent
         */
        Path fileOf(final JsonObject theArgumentsJson, final String theProperty)
            throws IOException {
            final String relative = theArgumentsJson.get(theProperty) == null
                ? null : theArgumentsJson.get(theProperty).getAsString();
            if(relative == null || relative.isBlank()) {
                throw new IOException(theProperty + " is required");
            }
            final Path file = root.resolve(relative).normalize();
            if(!file.startsWith(root)) {
                throw new IOException("path escapes the workspace: " + relative);
            }
            return file;
        }
    }

    private static final class ReadFileTool extends FileTool {

        ReadFileTool(final Path theRoot) {
            super(theRoot);
        }

        @Override
        public String getName() {
            return READ_FILE;
        }

        @Override
        public boolean isParallelSafe() {
            return true;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(READ_FILE,
                "Read a workspace file as text. Pass the workspace-relative path.",
                objectSchema("path"));
        }

        @Override
        public String execute(final String theArgumentsJson) throws ToolExecutionException {
            try {
                final JsonObject args = JsonParser.parseString(theArgumentsJson).getAsJsonObject();
                return Files.readString(fileOf(args, "path"), StandardCharsets.UTF_8);
            } catch (final IOException ex) {
                throw new ToolExecutionException("read failed", ex);
            }
        }
    }

    private static final class EditFileTool extends FileTool {

        EditFileTool(final Path theRoot) {
            super(theRoot);
        }

        @Override
        public String getName() {
            return EDIT_FILE;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(EDIT_FILE,
                "Replace every occurrence of search with replace inside one workspace file.",
                objectSchema("path", "search", "replace"));
        }

        @Override
        public String execute(final String theArgumentsJson) throws ToolExecutionException {
            try {
                final JsonObject args = JsonParser.parseString(theArgumentsJson).getAsJsonObject();
                final Path file = fileOf(args, "path");
                final String search = args.get("search").getAsString();
                final String replace = args.get("replace").getAsString();
                final String content = Files.readString(file, StandardCharsets.UTF_8);
                if(!content.contains(search)) {
                    return "ERROR: '" + search + "' does not occur in the file";
                }
                final int occurrences = content.split(Pattern.quote(search), -1).length - 1;
                Files.writeString(file, content.replace(search, replace), StandardCharsets.UTF_8);
                return "replaced " + occurrences + " occurrence(s)";
            } catch (final IOException ex) {
                throw new ToolExecutionException("edit failed", ex);
            }
        }
    }

    /**
     * @param theProperties string properties the model must pass
     * @return an object schema requiring every named string property
     */
    private static JsonObject objectSchema(final String... theProperties) {
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        final JsonObject properties = new JsonObject();
        final JsonArray required = new JsonArray();
        for(final String property : theProperties) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("type", "string");
            properties.add(property, entry);
            required.add(property);
        }
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    /**
     * Loads the model into memory once before the measured run: a cold model
     * pays the weight load inside the first round-trip, while the two-minute
     * budget is meant to bound the agent's behaviour, not the server's
     * startup. The warm-up is one short request without the thinking pass,
     * so it costs the harness a moment instead of a full generation.
     *
     * @param theProvider provider the measured run will answer from
     * @throws LLMException when even the warm-up call fails: the measured run
     *             would face the same dead endpoint anyway
     */
    private static void warmUpGenerator(final OllamaLLMProvider theProvider) throws LLMException {
        theProvider.chat(ChatRequest.builder(MODEL)
            .addMessage(ChatMessage.user("reply with the single word pong"))
            .think(false)
            .maxTokens(16)
            .build());
    }

    /**
     * Attaches a {@code java.util.logging} handler to the plugin's log namespace
     * so everything {@code ColleagueLog} writes while unbound lands in the given
     * sink. The harness needs the production trace as evidence; with the facade
     * in place the records come from JUL, not from stdout.
     *
     * @param theSink byte sink receiving one UTF-8 line per log record
     * @return handle that detaches the handler when closed
     */
    private static AutoCloseable captureFacadeLogs(final ByteArrayOutputStream theSink) {
        final java.util.logging.Logger jul =
            java.util.logging.Logger.getLogger("com.mad.edt.colleague");
        final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(final java.util.logging.LogRecord theRecord) {
                theSink.writeBytes((String.valueOf(theRecord.getMessage())
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void flush() {
                // the sink is a byte array, nothing buffered
            }

            @Override
            public void close() {
                // the sink outlives the handler; nothing to release
            }
        };
        final boolean priorUseParent = jul.getUseParentHandlers();
        final java.util.logging.Level priorLevel = jul.getLevel();
        jul.addHandler(handler);
        jul.setUseParentHandlers(false);
        jul.setLevel(java.util.logging.Level.ALL);
        return () -> {
            jul.removeHandler(handler);
            jul.setUseParentHandlers(priorUseParent);
            jul.setLevel(priorLevel);
        };
    }

    @Test
    @DisplayName("a real local model reads and repairs a workspace file end to end")
    void realModelRepairsAFile(@TempDir final Path theWorkspace) throws Exception {
        final Path module = theWorkspace.resolve("src/invoice.bsl");
        Files.createDirectories(module.getParent());
        Files.writeString(module, """
            Procedure Compute(final theAmount)
                Return BrokenHelper(theAmount) * 2;
            EndProcedure

            Procedure BrokenHelper(final theValue)
                Return theValue + 1;
            EndProcedure
            """, StandardCharsets.UTF_8);

        final OllamaLLMProvider provider = new OllamaLLMProvider(ProviderConfig
            .builder("e2e", LLMProviderType.OLLAMA)
            .baseUrl(SERVER_URL)
            .defaultModel(MODEL)
            .readTimeoutMs(300_000)
            .build());
        Assumptions.assumeTrue(provider.isAvailable(),
            "COLLEAGUE_E2E=1 but no model server answers at " + SERVER_URL);
        Assumptions.assumeTrue(provider.listModels().contains(MODEL),
            "model " + MODEL + " is not installed on the endpoint");
        warmUpGenerator(provider);

        final RecordingObserver recorder = new RecordingObserver();
        final AgentLoop loop = new AgentLoop(provider,
            List.of(new ReadFileTool(theWorkspace), new EditFileTool(theWorkspace)),
            MODEL, 8, new CancellationFlag(), recorder);

        final ByteArrayOutputStream console = new ByteArrayOutputStream();
        final AgentAnswer answer;
        final long startedAtNanos = System.nanoTime();
        try (AutoCloseable ignored = captureFacadeLogs(console)) {
            answer = loop.run(
                "You fix code in a workspace using tools. Always call the tools,"
                    + " never describe changes in prose.",
                "The workspace file src/invoice.bsl calls a procedure named " + BROKEN_NAME
                    + " that must be named " + FIXED_NAME + " instead. First call read_file"
                    + " with path src/invoice.bsl. Then call edit_file with path"
                    + " src/invoice.bsl, search " + BROKEN_NAME + ", replace " + FIXED_NAME
                    + ". When the edit tool says replaced, answer exactly DONE.");
        }
        // replay the captured production trace into the build log: an
        // unattended run must leave the step-by-step evidence behind, not
        // only a passed/failed line
        java.util.logging.Logger.getLogger("com.mad.edt.colleague.e2e")
            .info(() -> console.toString(StandardCharsets.UTF_8));

        final java.util.function.Supplier<String> trace =
            () -> String.join("\n", recorder.events);
        Assertions.assertTrue(console.toString(StandardCharsets.UTF_8)
            .contains("[colleague] agent step"),
            "the log observer printed no step line during the run; harness trace:\n"
                + trace.get());

        Assertions.assertTrue(recorder.usedTool(READ_FILE),
            "the model never read the file; trace:\n" + trace.get());
        Assertions.assertTrue(recorder.usedTool(EDIT_FILE),
            "the model never used the edit tool; trace:\n" + trace.get());
        Assertions.assertEquals(READ_FILE, recorder.firstTool(),
            "reading before editing is the required order; trace:\n" + trace.get());

        final String fixedSource = Files.readString(module, StandardCharsets.UTF_8);
        Assertions.assertFalse(fixedSource.contains(BROKEN_NAME),
            "the workspace file still carries the broken identifier; trace:\n" + trace.get());
        Assertions.assertTrue(fixedSource.contains(FIXED_NAME),
            "the fix was never written; trace:\n" + trace.get());

        Assertions.assertTrue(answer.isCompleted(),
            "the loop never produced a final answer; trace:\n" + trace.get());
        Assertions.assertFalse(answer.getText().isBlank(), "the final answer is empty");

        /*
         * Performance bound of the turn, the regression net of the whole P
         * track. The scenario is fixed: one read round, one edit round, one
         * final answer round, so a healthy run is three round-trips and any
         * fourth one means the model wandered - an empty final round paid
         * for a repair, or a tool call came malformed. The wall-clock budget
         * of two minutes comes from the board measurements of a warmed
         * qwen3:4b on this harness: comfortably above a good run, far below
         * what a cold model load, a repair round eating an 8k budget or a
         * generator fight would cost. Both thresholds only ever tighten the
         * old "completed at all" bar, never the other way.
         */
        final long totalMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        Assertions.assertTrue(answer.getSteps() <= 3,
            "the turn took " + answer.getSteps() + " round-trips, the fixed scenario needs"
                + " at most three (read, edit, answer); trace:\n" + trace.get());
        Assertions.assertTrue(totalMs < 120_000L,
            "the turn took " + totalMs + " ms, over the 120000 ms budget of a warmed"
                + " model; trace:\n" + trace.get());
    }

    @Test
    @DisplayName("a real local model delegates a read-only task and reports the delegate answer")
    void realModelDelegatesToASubagent(@TempDir final Path theWorkspace) throws Exception {
        final Path note = theWorkspace.resolve("src/secret.txt");
        Files.createDirectories(note.getParent());
        Files.writeString(note, UMBRELLA, StandardCharsets.UTF_8);

        final OllamaLLMProvider provider = new OllamaLLMProvider(ProviderConfig
            .builder("e2e", LLMProviderType.OLLAMA)
            .baseUrl(SERVER_URL)
            .defaultModel(MODEL)
            .readTimeoutMs(300_000)
            .build());
        Assumptions.assumeTrue(provider.isAvailable(),
            "COLLEAGUE_E2E=1 but no model server answers at " + SERVER_URL);
        Assumptions.assumeTrue(provider.listModels().contains(MODEL),
            "model " + MODEL + " is not installed on the endpoint");
        warmUpGenerator(provider);

        final RecordingObserver recorder = new RecordingObserver();
        /*
         * The parent is offered the writer as well: the delegate must never
         * see it, so the whole trace staying free of edit_file calls while
         * the delegate still reads the file is the proof of the read-only
         * boundary, not just a property of how the tools were wired.
         */
        final SubagentTool subagent = new SubagentTool(provider, MODEL,
            List.of(new ReadFileTool(theWorkspace), new EditFileTool(theWorkspace)),
            List.of(), 0, new CancellationFlag());
        final AgentLoop loop = new AgentLoop(provider,
            List.of(new ReadFileTool(theWorkspace), new EditFileTool(theWorkspace), subagent),
            MODEL, 6, new CancellationFlag(), recorder);

        final ByteArrayOutputStream console = new ByteArrayOutputStream();
        final AgentAnswer answer;
        final long startedAtNanos = System.nanoTime();
        try (AutoCloseable ignored = captureFacadeLogs(console)) {
            answer = loop.run(
                "You answer questions using tools. Always call the tools,"
                    + " never describe actions in prose.",
                "The secret word is stored in the workspace file src/secret.txt."
                    + " Do NOT read it yourself. Call the subagent tool with a prompt"
                    + " that tells the delegate to call read_file with path"
                    + " src/secret.txt and answer with the exact word it read."
                    + " When the subagent result comes back, answer with exactly that"
                    + " word and nothing else.");
        }
        java.util.logging.Logger.getLogger("com.mad.edt.colleague.e2e")
            .info(() -> console.toString(StandardCharsets.UTF_8));

        final java.util.function.Supplier<String> trace =
            () -> String.join("\n", recorder.events);
        Assertions.assertTrue(recorder.usedTool("subagent"),
            "the model never delegated; trace:\n" + trace.get());
        final String delegateResult = recorder.events.stream()
            .filter(event -> event.contains(RecordingObserver.TOOL_EVENT + "subagent"))
            .findFirst().orElse("");
        Assertions.assertTrue(delegateResult.contains(UMBRELLA),
            "the delegate answer did not come back as the tool result; trace:\n"
                + trace.get());
        Assertions.assertFalse(recorder.usedTool(EDIT_FILE),
            "a write tool reached the delegate or the parent rewrote the file; trace:\n"
                + trace.get());
        Assertions.assertEquals(UMBRELLA, Files.readString(note, StandardCharsets.UTF_8),
            "the delegate must not change the file it reads");
        Assertions.assertTrue(answer.getText().contains(UMBRELLA),
            "the parent did not report the delegate's answer; trace:\n" + trace.get());
        final long totalMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        Assertions.assertTrue(totalMs < 180_000L,
            "the delegation took " + totalMs + " ms, over the 180000 ms budget;"
                + " trace:\n" + trace.get());
    }
}
