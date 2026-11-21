package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.http.HttpTransport;

/**
 * Talks to any HTTP endpoint implementing the OpenAI-compatible chat
 * completions contract. The base URL must already contain the API version
 * segment, e.g. {@code https://host/v1}.
 */
public class OpenAiCompatibleLLMProvider extends AbstractHttpLLMProvider {

    private static final int PING_TIMEOUT_MS = 10_000;

    /** OpenAI-compatible chat chunk and request field names. */
    private static final String MODEL = "model";
    private static final String FINISH_REASON = "finish_reason";
    private static final String CONTENT = "content";
    private static final String TOOL_CALLS = "tool_calls";
    private static final String FUNCTION = "function";
    private static final String ARGUMENTS = "arguments";

    /**
     * @param theConfig endpoint configuration; base URL ends with the API
     *        version segment, e.g. {@code https://host/v1}
     */
    public OpenAiCompatibleLLMProvider(final ProviderConfig theConfig) {
        this(theConfig, HttpTransport.createDefault());
    }

    /**
     * @param theConfig endpoint configuration
     * @param theTransport transport seam used for every call
     */
    public OpenAiCompatibleLLMProvider(final ProviderConfig theConfig,
        final HttpTransport theTransport) {
        super(theConfig, theTransport);
    }

    /**
     * A gateway-style endpoint answers 404 to {@code /chat/completions} when
     * the saved address stopped short of the API version segment - the path
     * itself is part of the contract, so an address like {@code host:port}
     * alone is the single most common way to misconfigure this dialect. The
     * hint spells out the address that would work.
     */
    @Override
    protected String failureHint(final int theStatus) {
        if(theStatus != 404 || getConfig().getBaseUrl().contains("/v1")) {
            return "";
        }
        return " — у OpenAI-совместимого эндпоинта адрес сервиса должен включать"
            + " путь версии API, например " + getConfig().getBaseUrl() + "/v1";
    }

    @Override
    public ChatResult chat(final ChatRequest theRequest) throws LLMException {
        if(theRequest.getCancellation().isCancelled()) {
            throw new LLMException("generation turn cancelled");
        }
        final JsonObject response =
            postJson("/chat/completions", buildBody(theRequest, false),
                getConfig().getReadTimeoutMs());
        return parseResult(response);
    }

    @Override
    public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
        final Consumer<String> theOnThinking) throws LLMException {
        final StreamState state = new StreamState();
        try {
            postJsonStream("/chat/completions", buildBody(theRequest, true),
                getConfig().getReadTimeoutMs(), theRequest.getCancellation(),
                chunk -> applyChunk(chunk, state, theOnContent, theOnThinking));
        } catch (final TurnCancelledException ex) {
            // the user stopped this turn: the partial text produced so far is
            // kept, and the caller recognizes the stop via its own flag
        }
        return state.toResult();
    }

    /**
     * Folds one streamed chunk into the accumulators of the round.
     *
     * @param theChunk parsed chunk object
     * @param theState accumulators of the round
     * @param theOnContent receives answer chunks
     * @param theOnThinking receives reasoning chunks
     */
    private static void applyChunk(final JsonObject theChunk, final StreamState theState,
        final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
        if(theChunk.has(MODEL)) {
            theState.modelName[0] = textOrNull(theChunk, MODEL);
        }
        final JsonObject usage = theChunk.getAsJsonObject("usage");
        if(usage != null) {
            theState.promptTokens[0] = intOrZero(usage, "prompt_tokens");
            theState.completionTokens[0] = intOrZero(usage, "completion_tokens");
        }
        final JsonArray choices = theChunk.getAsJsonArray("choices");
        if(choices == null || choices.isEmpty()) {
            return;
        }
        for(final JsonElement element : choices) {
            applyChoice(element.getAsJsonObject(), theState, theOnContent, theOnThinking);
        }
    }

    /**
     * @param theChoice one choice entry of a chunk
     * @param theState accumulators of the round
     * @param theOnContent receives answer chunks
     * @param theOnThinking receives reasoning chunks
     */
    private static void applyChoice(final JsonObject theChoice, final StreamState theState,
        final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
        if(theChoice.has(FINISH_REASON) && !theChoice.get(FINISH_REASON).isJsonNull()) {
            theState.finishReason[0] = textOrNull(theChoice, FINISH_REASON);
        }
        final JsonObject delta = theChoice.getAsJsonObject("delta");
        if(delta == null) {
            return;
        }
        final String piece = textOrNull(delta, CONTENT);
        if(piece != null && !piece.isEmpty()) {
            theState.content.append(piece);
            theOnContent.accept(piece);
        }
        String thought = textOrNull(delta, "reasoning_content");
        if(thought == null) {
            // some gateways spell the reasoning delta plainly
            thought = textOrNull(delta, "reasoning");
        }
        if(thought != null && !thought.isEmpty()) {
            theState.thinking.append(thought);
            theOnThinking.accept(thought);
        }
        final JsonArray calls = delta.getAsJsonArray(TOOL_CALLS);
        if(calls == null) {
            return;
        }
        for(final JsonElement callElement : calls) {
            mergeCallPart(callElement.getAsJsonObject(), theState);
        }
    }

    /**
     * Merges a streamed tool-call fragment into the parts collected for its
     * position: id and name arrive once, arguments arrive in pieces.
     *
     * @param theCall fragment of one tool call
     * @param theState accumulators of the round
     */
    private static void mergeCallPart(final JsonObject theCall, final StreamState theState) {
        final int position = theCall.has("index")
            ? theCall.get("index").getAsInt() : Integer.valueOf(theState.callParts.size());
        final JsonObject function = theCall.getAsJsonObject(FUNCTION);
        final String[] parts = theState.callParts.computeIfAbsent(
            Integer.valueOf(position), key -> new String[2]);
        if(theCall.has("id") && !theCall.get("id").isJsonNull()) {
            parts[0] = textOrNull(theCall, "id");
        }
        if(function == null) {
            return;
        }
        if(textOrNull(function, "name") != null) {
            parts[1] = textOrNull(function, "name");
        }
        final String argumentPiece = textOrNull(function, ARGUMENTS);
        if(argumentPiece != null) {
            theState.callArguments.computeIfAbsent(Integer.valueOf(position),
                key -> new StringBuilder()).append(argumentPiece);
        }
    }

    /** Mutable accumulators of one streamed round. */
    private static final class StreamState {

        private final StringBuilder content = new StringBuilder();

        private final StringBuilder thinking = new StringBuilder();

        private final String[] modelName = new String[1];

        private final String[] finishReason = new String[1];

        private final int[] promptTokens = new int[1];

        private final int[] completionTokens = new int[1];

        private final java.util.Map<Integer, String[]> callParts = new java.util.TreeMap<>();

        private final java.util.Map<Integer, StringBuilder> callArguments = new java.util.TreeMap<>();

        /**
         * @return the assembled answer with the tool calls closed over the
         *         collected call parts
         */
        ChatResult toResult() {
            final List<ToolCall> calls = new ArrayList<>();
            for(final java.util.Map.Entry<Integer, String[]> entry : callParts.entrySet()) {
                final StringBuilder arguments = callArguments.get(entry.getKey());
                calls.add(new ToolCall(entry.getValue()[0], entry.getValue()[1],
                    arguments == null || arguments.length() == 0 ? "{}" : arguments.toString()));
            }
            return new ChatResult(content.toString(),
                thinking.length() == 0 ? null : thinking.toString(),
                modelName[0], finishReason[0],
                promptTokens[0], completionTokens[0], calls);
        }
    }

    private JsonObject buildBody(final ChatRequest theRequest, final boolean theStreaming)
        throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty(MODEL, resolveModel(theRequest.getModel()));
        body.addProperty("stream", Boolean.valueOf(theStreaming));
        if(theRequest.getTemperature() != null) {
            body.addProperty("temperature", theRequest.getTemperature().doubleValue());
        }
        if(theRequest.getMaxTokens() != null) {
            body.addProperty("max_tokens", theRequest.getMaxTokens().intValue());
        }
        appendTools(body, theRequest);
        final JsonArray messages = new JsonArray();
        for(final ChatMessage message : theRequest.getMessages()) {
            appendMessageEntry(messages, message);
        }
        body.add("messages", messages);
        return body;
    }

    /**
     * @param theBody request body being built
     * @param theRequest chat request
     */
    private static void appendTools(final JsonObject theBody, final ChatRequest theRequest) {
        if(theRequest.getTools().isEmpty()) {
            return;
        }
        final JsonArray tools = new JsonArray();
        for(final JsonObject tool : ToolSpec.toToolArray(theRequest.getTools())) {
            tools.add(tool);
        }
        theBody.add("tools", tools);
    }

    /**
     * @param theMessages messages array being built
     * @param theMessage message to append as one entry
     */
    private static void appendMessageEntry(final JsonArray theMessages, final ChatMessage theMessage) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("role", theMessage.getRole());
        if(theMessage.hasToolCalls()) {
            entry.add(TOOL_CALLS, toolCallsArray(theMessage.getToolCalls()));
            entry.add(CONTENT, com.google.gson.JsonNull.INSTANCE);
        } else {
            entry.addProperty(CONTENT, theMessage.getContent());
            if(theMessage.getToolCallId() != null) {
                entry.addProperty("tool_call_id", theMessage.getToolCallId());
            }
        }
        theMessages.add(entry);
    }

    /**
     * @param theCalls tool calls of an assistant message
     * @return their array in the shape the endpoint expects, ids generated
     *         for the calls that carry none
     */
    private static JsonArray toolCallsArray(final List<ToolCall> theCalls) {
        final JsonArray calls = new JsonArray();
        int index = 0;
        for(final ToolCall call : theCalls) {
            final JsonObject function = new JsonObject();
            function.addProperty("name", call.getName());
            function.addProperty(ARGUMENTS, call.getArgumentsJson());
            final JsonObject wrapper = new JsonObject();
            wrapper.addProperty("id", call.getId() != null ? call.getId() : "call_" + index);
            wrapper.addProperty("type", FUNCTION);
            wrapper.add(FUNCTION, function);
            calls.add(wrapper);
            index++;
        }
        return calls;
    }

    private ChatResult parseResult(final JsonObject theResponse) {
        String content = "";
        String thinking = null;
        String finishReason = null;
        List<ToolCall> calls = List.of();
        final JsonArray choices = theResponse.getAsJsonArray("choices");
        if(choices != null && !choices.isEmpty()) {
            final JsonObject choice = choices.get(0).getAsJsonObject();
            finishReason = textOrNull(choice, FINISH_REASON);
            final JsonObject message = choice.getAsJsonObject("message");
            if(message != null) {
                content = textOrNull(message, CONTENT);
                if(content == null) {
                    content = "";
                }
                thinking = textOrNull(message, "reasoning_content");
                if(thinking == null) {
                    thinking = textOrNull(message, "reasoning");
                }
                calls = parseToolCalls(message.getAsJsonArray(TOOL_CALLS));
            }
        }

        int promptTokens = 0;
        int completionTokens = 0;
        final JsonObject usage = theResponse.getAsJsonObject("usage");
        if(usage != null) {
            promptTokens = intOrZero(usage, "prompt_tokens");
            completionTokens = intOrZero(usage, "completion_tokens");
        }

        return new ChatResult(content, thinking, textOrNull(theResponse, MODEL),
            finishReason, promptTokens, completionTokens, calls);
    }

    @Override
    public List<String> listModels() throws LLMException {
        final JsonObject response = getJson("/models", getConfig().getReadTimeoutMs());
        final List<String> names = new ArrayList<>();
        final JsonArray data = response.getAsJsonArray("data");
        if(data != null) {
            for(final JsonElement entry : data) {
                final String id = textOrNull(entry.getAsJsonObject(), "id");
                if(id != null) {
                    names.add(id);
                }
            }
        }
        return names;
    }

    @Override
    public boolean isAvailable() {
        try {
            getJson("/models", PING_TIMEOUT_MS);
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
            final JsonObject function = call.getAsJsonObject(FUNCTION);
            final String name = function == null ? null : textOrNull(function, "name");
            if(name != null) {
                final String arguments = textOrNull(function, ARGUMENTS);
                calls.add(new ToolCall(textOrNull(call, "id"), name,
                    arguments == null ? "{}" : arguments));
            }
        }
        return calls;
    }
}
