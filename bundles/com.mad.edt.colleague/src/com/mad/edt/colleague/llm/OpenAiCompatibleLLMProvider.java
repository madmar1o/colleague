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
    public ChatResult chat(final ChatRequest theRequest) throws LLMException {        if(theRequest.getCancellation().isCancelled()) {
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
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final String[] modelName = new String[1];
        final String[] finishReason = new String[1];
        final int[] promptTokens = new int[1];
        final int[] completionTokens = new int[1];
        final java.util.Map<Integer, String[]> callParts = new java.util.TreeMap<>();
        final java.util.Map<Integer, StringBuilder> callArguments = new java.util.TreeMap<>();

        try {
            postJsonStream("/chat/completions", buildBody(theRequest, true),
                getConfig().getReadTimeoutMs(), theRequest.getCancellation(), chunk -> {
                if(chunk.has("model")) {
                    modelName[0] = textOrNull(chunk, "model");
                }
                final JsonObject usage = chunk.getAsJsonObject("usage");
                if(usage != null) {
                    promptTokens[0] = intOrZero(usage, "prompt_tokens");
                    completionTokens[0] = intOrZero(usage, "completion_tokens");
                }
                final JsonArray choices = chunk.getAsJsonArray("choices");
                if(choices == null || choices.isEmpty()) {
                    return;
                }
                choiceLoop: for(final JsonElement element : choices) {
                    final JsonObject choice = element.getAsJsonObject();
                    if(choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finishReason[0] = textOrNull(choice, "finish_reason");
                    }
                    final JsonObject delta = choice.getAsJsonObject("delta");
                    if(delta == null) {
                        continue choiceLoop;
                    }
                    final String piece = textOrNull(delta, "content");
                    if(piece != null && !piece.isEmpty()) {
                        content.append(piece);
                        theOnContent.accept(piece);
                    }
                    String thought = textOrNull(delta, "reasoning_content");
                    if(thought == null) {
                        // some gateways spell the reasoning delta plainly
                        thought = textOrNull(delta, "reasoning");
                    }
                    if(thought != null && !thought.isEmpty()) {
                        thinking.append(thought);
                        theOnThinking.accept(thought);
                    }
                    final JsonArray calls = delta.getAsJsonArray("tool_calls");
                    if(calls == null) {
                        continue choiceLoop;
                    }
                    for(final JsonElement callElement : calls) {
                        final JsonObject call = callElement.getAsJsonObject();
                        final int position = call.has("index")
                            ? call.get("index").getAsInt() : Integer.valueOf(callParts.size());
                        final JsonObject function = call.getAsJsonObject("function");
                        final String[] parts = callParts.computeIfAbsent(
                            Integer.valueOf(position), key -> new String[2]);
                        if(call.has("id") && !call.get("id").isJsonNull()) {
                            parts[0] = textOrNull(call, "id");
                        }
                        if(function != null) {
                            if(textOrNull(function, "name") != null) {
                                parts[1] = textOrNull(function, "name");
                            }
                            final String argumentPiece = textOrNull(function, "arguments");
                            if(argumentPiece != null) {
                                callArguments.computeIfAbsent(Integer.valueOf(position),
                                    key -> new StringBuilder()).append(argumentPiece);
                            }
                        }
                    }
                }
            });
        } catch (final TurnCancelledException ex) {
            // the user stopped this turn: the partial text produced so far is
            // kept, and the caller recognizes the stop via its own flag
        }

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

    private JsonObject buildBody(final ChatRequest theRequest, final boolean theStreaming)
        throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(theRequest.getModel()));
        body.addProperty("stream", Boolean.valueOf(theStreaming));
        if(theRequest.getTemperature() != null) {
            body.addProperty("temperature", theRequest.getTemperature().doubleValue());
        }
        if(theRequest.getMaxTokens() != null) {
            body.addProperty("max_tokens", theRequest.getMaxTokens().intValue());
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
                int index = 0;
                for(final ToolCall call : message.getToolCalls()) {
                    final JsonObject function = new JsonObject();
                    function.addProperty("name", call.getName());
                    function.addProperty("arguments", call.getArgumentsJson());
                    final JsonObject wrapper = new JsonObject();
                    wrapper.addProperty("id", call.getId() != null ? call.getId() : "call_" + index);
                    wrapper.addProperty("type", "function");
                    wrapper.add("function", function);
                    calls.add(wrapper);
                    index++;
                }
                entry.add("tool_calls", calls);
                entry.add("content", com.google.gson.JsonNull.INSTANCE);
            } else {
                entry.addProperty("content", message.getContent());
                if(message.getToolCallId() != null) {
                    entry.addProperty("tool_call_id", message.getToolCallId());
                }
            }
            messages.add(entry);
        }
        body.add("messages", messages);
        return body;
    }

    private ChatResult parseResult(final JsonObject theResponse) {
        String content = "";
        String thinking = null;
        String finishReason = null;
        List<ToolCall> calls = List.of();
        final JsonArray choices = theResponse.getAsJsonArray("choices");
        if(choices != null && !choices.isEmpty()) {
            final JsonObject choice = choices.get(0).getAsJsonObject();
            finishReason = textOrNull(choice, "finish_reason");
            final JsonObject message = choice.getAsJsonObject("message");
            if(message != null) {
                content = textOrNull(message, "content");
                if(content == null) {
                    content = "";
                }
                thinking = textOrNull(message, "reasoning_content");
                if(thinking == null) {
                    thinking = textOrNull(message, "reasoning");
                }
                calls = parseToolCalls(message.getAsJsonArray("tool_calls"));
            }
        }

        int promptTokens = 0;
        int completionTokens = 0;
        final JsonObject usage = theResponse.getAsJsonObject("usage");
        if(usage != null) {
            promptTokens = intOrZero(usage, "prompt_tokens");
            completionTokens = intOrZero(usage, "completion_tokens");
        }

        return new ChatResult(content, thinking, textOrNull(theResponse, "model"),
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
            final JsonObject function = call.getAsJsonObject("function");
            if(function == null) {
                continue;
            }
            final String name = textOrNull(function, "name");
            if(name == null) {
                continue;
            }
            final String arguments = textOrNull(function, "arguments");
            calls.add(new ToolCall(textOrNull(call, "id"), name,
                arguments == null ? "{}" : arguments));
        }
        return calls;
    }
}
