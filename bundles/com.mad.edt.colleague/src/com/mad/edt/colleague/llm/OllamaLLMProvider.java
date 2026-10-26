package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.http.HttpTransport;

/**
 * Talks to the native local model runtime ({@code /api/chat}, {@code /api/tags}).
 */
public class OllamaLLMProvider extends AbstractHttpLLMProvider {

    private static final int PING_TIMEOUT_MS = 5_000;

    /** Budget for one warm-up call: loading a model into memory is slow. */
    private static final int WARM_TIMEOUT_MS = 120_000;

    /**
     * @param theConfig endpoint configuration; base URL must point at the
     *        server root, e.g. {@code http://127.0.0.1:11435}
     */
    public OllamaLLMProvider(final ProviderConfig theConfig) {
        this(theConfig, HttpTransport.createDefault());
    }

    /**
     * @param theConfig endpoint configuration
     * @param theTransport transport seam used for every call
     */
    public OllamaLLMProvider(final ProviderConfig theConfig, final HttpTransport theTransport) {
        super(theConfig, theTransport);
    }

    @Override
    public ChatResult chat(final ChatRequest theRequest) throws LLMException {
        if(theRequest.getCancellation().isCancelled()) {
            throw new LLMException("generation turn cancelled");
        }
        final JsonObject body = buildBody(theRequest, false);
        final JsonObject response =
            postJson("/api/chat", body, getConfig().getReadTimeoutMs());

        String content = "";
        String thinking = null;
        List<ToolCall> calls = List.of();
        final JsonObject message = response.getAsJsonObject("message");
        if(message != null) {
            content = textOrNull(message, "content");
            if(content == null) {
                content = "";
            }
            thinking = textOrNull(message, "thinking");
            calls = parseToolCalls(message.getAsJsonArray("tool_calls"));
        }
        return new ChatResult(content, thinking, textOrNull(response, "model"),
            textOrNull(response, "done_reason"),
            intOrZero(response, "prompt_eval_count"), intOrZero(response, "eval_count"), calls);
    }

    @Override
    public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
        final Consumer<String> theOnThinking) throws LLMException {
        final JsonObject body = buildBody(theRequest, true);
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final String[] modelName = new String[1];
        final String[] doneReason = new String[1];
        final int[] promptTokens = new int[1];
        final int[] completionTokens = new int[1];
        final JsonArray lastToolCalls = new JsonArray();

        try {
            postJsonStream("/api/chat", body, getConfig().getReadTimeoutMs(),
                theRequest.getCancellation(), event -> {
            final JsonObject message = event.getAsJsonObject("message");
            if(message != null) {
                final String thought = textOrNull(message, "thinking");
                if(thought != null && !thought.isEmpty()) {
                    thinking.append(thought);
                    theOnThinking.accept(thought);
                }
                final String chunk = textOrNull(message, "content");
                if(chunk != null && !chunk.isEmpty()) {
                    content.append(chunk);
                    theOnContent.accept(chunk);
                }
                final JsonArray calls = message.getAsJsonArray("tool_calls");
                if(calls != null && !calls.isEmpty()) {
                    lastToolCalls.addAll(calls);
                }
            }
            if(event.has("model")) {
                modelName[0] = textOrNull(event, "model");
            }
            if(event.get("done") != null && event.get("done").getAsBoolean()) {
                doneReason[0] = textOrNull(event, "done_reason");
                promptTokens[0] = intOrZero(event, "prompt_eval_count");
                completionTokens[0] = intOrZero(event, "eval_count");
            }
            });
        } catch (final TurnCancelledException ex) {
            // the user stopped this turn: the partial text produced so far is
            // kept, and the caller recognizes the stop via its own flag
        }

        return new ChatResult(content.toString(),
            thinking.length() == 0 ? null : thinking.toString(),
            modelName[0], doneReason[0],
            promptTokens[0], completionTokens[0], parseToolCalls(lastToolCalls));
    }

    @Override
    public void warmModel(final String theModel) throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(theModel));
        // buffered answer, one JSON document: the point is the load itself,
        // not the generation, so the request carries no prompt at all
        body.addProperty("stream", Boolean.FALSE);
        appendKeepAlive(body);
        appendWarmWindow(body);
        postJson("/api/generate", body, WARM_TIMEOUT_MS);
    }

    /**
     * Claims model residence for {@link ProviderConfig#getKeepAlive()} after
     * this call, so the endpoint keeps the weights loaded and the next
     * question does not pay the cold load.
     *
     * @param theBody request body to extend
     */
    private void appendKeepAlive(final JsonObject theBody) {
        final String keepAlive = getConfig().getKeepAlive();
        if(keepAlive != null && !keepAlive.isEmpty()) {
            theBody.addProperty("keep_alive", keepAlive);
        }
    }

    /**
     * Reserves the configured minimum token window while the model loads, so
     * the first real question never stalls an answer on memory widening.
     *
     * @param theBody request body to extend
     */
    private void appendWarmWindow(final JsonObject theBody) {
        final int window = getConfig().getContextWindowTokens();
        if(window > 0) {
            final JsonObject options = new JsonObject();
            options.addProperty("num_ctx", Integer.valueOf(window));
            theBody.add("options", options);
        }
    }

    /**
     * The token window this call needs: at least the configured floor,
     * widened to cover the prompt when it is roomier than that, never past
     * the memory ceiling. Left at the runtime's own tiny default the oldest
     * prompt text is cut away in silence, and the answer reads as if the
     * workspace context had never been attached.
     *
     * @param theMessages the message list going into this call
     * @return window in tokens, {@code 0} when the option must not be sent
     */
    private int requestedWindowTokens(final JsonArray theMessages) {
        final int floor = getConfig().getContextWindowTokens();
        if(floor <= 0) {
            return 0;
        }
        final int estimated = estimatedPromptTokens(theMessages);
        if(estimated <= floor) {
            return floor;
        }
        final int rounded = (estimated + 1_023) / 1_024 * 1_024;
        return Math.min(rounded, getConfig().getContextWindowCeiling());
    }

    /**
     * An upper bound on what the message list costs the runtime's tokenizer:
     * mixed plain and source text tokenizes at well under one token per
     * character, and a flat margin covers the chat template wrapped around
     * the messages.
     *
     * @param theMessages the message list going into this call
     * @return estimated token count
     */
    private static int estimatedPromptTokens(final JsonArray theMessages) {
        int characters = 0;
        for(final JsonElement entry : theMessages) {
            final String content = textOrNull(entry.getAsJsonObject(), "content");
            if(content != null) {
                characters += content.length();
            }
        }
        return characters * 3 / 5 + 256;
    }

    private JsonObject buildBody(final ChatRequest theRequest, final boolean theStreaming)
        throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(theRequest.getModel()));
        body.addProperty("stream", Boolean.valueOf(theStreaming));
        appendKeepAlive(body);
        if(theRequest.getThink() != null) {
            body.addProperty("think", theRequest.getThink().booleanValue());
        }
        if(theRequest.getTemperature() != null) {
            body.addProperty("temperature", theRequest.getTemperature().doubleValue());
        }
        if(!theRequest.getTools().isEmpty()) {
            final JsonArray tools = new JsonArray();
            for(final JsonObject tool : ToolSpec.toToolArray(theRequest.getTools())) {
                tools.add(tool);
            }
            body.add("tools", tools);
        }

        final JsonArray messages = new JsonArray();
        for(final ChatMessage message : theRequest.getMessages()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole());
            if(message.hasToolCalls()) {
                final JsonArray calls = new JsonArray();
                for(final ToolCall call : message.getToolCalls()) {
                    final JsonObject function = new JsonObject();
                    function.addProperty("name", call.getName());
                    function.add("arguments", parseArguments(call.getArgumentsJson()));
                    final JsonObject wrapper = new JsonObject();
                    if(call.getId() != null) {
                        wrapper.addProperty("id", call.getId());
                    }
                    wrapper.add("function", function);
                    calls.add(wrapper);
                }
                entry.add("tool_calls", calls);
                entry.addProperty("content", message.getContent());
            } else {
                entry.addProperty("content", message.getContent());
                if(message.getToolCallId() != null) {
                    entry.addProperty("tool_call_id", message.getToolCallId());
                }
            }
            messages.add(entry);
        }
        body.add("messages", messages);
        final JsonObject options = new JsonObject();
        final int window = requestedWindowTokens(messages);
        if(window > 0) {
            options.addProperty("num_ctx", Integer.valueOf(window));
        }
        if(theRequest.getMaxTokens() != null) {
            options.addProperty("num_predict", theRequest.getMaxTokens().intValue());
        }
        if(!options.isEmpty()) {
            body.add("options", options);
        }
        return body;
    }

    @Override
    public List<String> listModels() throws LLMException {
        final JsonObject response = getJson("/api/tags", getConfig().getReadTimeoutMs());
        final List<String> names = new ArrayList<>();
        final JsonArray models = response.getAsJsonArray("models");
        if(models != null) {
            for(final JsonElement entry : models) {
                final String name = textOrNull(entry.getAsJsonObject(), "name");
                if(name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public boolean isAvailable() {
        try {
            getJson("/api/version", PING_TIMEOUT_MS);
            return true;
        } catch (final LLMException ex) {
            return false;
        }
    }

    private static List<ToolCall> parseToolCalls(final JsonArray theCalls) {
        final List<ToolCall> calls = new ArrayList<>();
        if(theCalls == null) {
            return calls;
        }
        for(final JsonElement element : theCalls) {
            final JsonObject call = element.getAsJsonObject();
            final JsonObject function = call.getAsJsonObject("function");
            if(function == null) {
                continue;
            }
            final JsonElement arguments = function.get("arguments");
            final String argumentsJson;
            if(arguments == null || arguments.isJsonNull()) {
                argumentsJson = "{}";
            } else if(arguments.isJsonPrimitive()) {
                argumentsJson = arguments.getAsString();
            } else {
                argumentsJson = arguments.toString();
            }
            final String name = textOrNull(function, "name");
            if(name == null) {
                continue;
            }
            calls.add(new ToolCall(textOrNull(call, "id"), name, argumentsJson));
        }
        return calls;
    }
}
