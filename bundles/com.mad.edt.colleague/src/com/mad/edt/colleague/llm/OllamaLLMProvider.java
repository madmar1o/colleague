package com.mad.edt.colleague.llm;

import com.mad.edt.colleague.ColleagueLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Capability name the runtime reports for a model that can run a
     * separate reasoning pass. Read from the endpoint's own description of
     * the model, never guessed from the model name.
     */
    private static final String THINKING_CAPABILITY = "thinking";
    /** Ollama chat payload field names. */
    private static final String CONTENT = "content";
    private static final String TOOL_CALLS = "tool_calls";
    private static final String MODEL = "model";

    /** Capability name the runtime reports for a prompt-completing model. */
    private static final String COMPLETION_CAPABILITY = "completion";

    /** Budget for one warm-up call: loading a model into memory is slow. */
    private static final int WARM_TIMEOUT_MS = 120_000;

    /**
     * Models this endpoint rejected the reasoning pass for. The rejection is
     * permanent for the model, so the answer is remembered once per provider
     * and every later round starts without asking.
     */
    private final Set<String> ourNoThinking = ConcurrentHashMap.newKeySet();

    /**
     * Capabilities the endpoint reports for a model, cached per model. The
     * runtime describes its models itself ({@code /api/show}), which is the
     * only reliable source: a model name says nothing about whether it can
     * run a reasoning pass.
     */
    private final Map<String, Set<String>> ourCapabilities = new ConcurrentHashMap<>();

    /**
     * @param theConfig endpoint configuration; base URL must point at the
     *        server root, e.g. {@code http://127.0.0.1:11434}
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
        try {
            return bufferedRound(theRequest, effectiveThink(theRequest));
        } catch (final LLMException ex) {
            if(!isThinkingRejected(ex, effectiveThink(theRequest))) {
                throw ex;
            }
            forgetThinking(theRequest);
            return bufferedRound(theRequest, Boolean.FALSE);
        }
    }

    /**
     * One buffered round over the given reasoning setting.
     *
     * @param theRequest the request
     * @param theThink whether the reasoning pass is requested, {@code null}
     *            leaves the field out of the body
     * @return the parsed answer
     * @throws LLMException when the endpoint is unreachable, rejects or
     *             returns an unexpected body
     */
    private ChatResult bufferedRound(final ChatRequest theRequest, final Boolean theThink)
        throws LLMException {
        final JsonObject body = buildBody(theRequest, false, theThink);
        final JsonObject response =
            postJson("/api/chat", body, getConfig().getReadTimeoutMs());

        String content = "";
        String thinking = null;
        List<ToolCall> calls = List.of();
        final JsonObject message = response.getAsJsonObject("message");
        if(message != null) {
            content = textOrNull(message, CONTENT);
            if(content == null) {
                content = "";
            }
            thinking = textOrNull(message, THINKING_CAPABILITY);
            calls = parseToolCalls(message.getAsJsonArray(TOOL_CALLS));
        }
        return new ChatResult(content, thinking, textOrNull(response, MODEL),
            textOrNull(response, "done_reason"),
            intOrZero(response, "prompt_eval_count"), intOrZero(response, "eval_count"), calls);
    }

    @Override
    public ChatResult chatStream(final ChatRequest theRequest, final Consumer<String> theOnContent,
        final Consumer<String> theOnThinking) throws LLMException {
        try {
            return streamedRound(theRequest, theOnContent, theOnThinking, effectiveThink(theRequest));
        } catch (final LLMException ex) {
            if(!isThinkingRejected(ex, effectiveThink(theRequest))) {
                throw ex;
            }
            forgetThinking(theRequest);
            return streamedRound(theRequest, theOnContent, theOnThinking, Boolean.FALSE);
        }
    }

    /**
     * One streamed round over the given reasoning setting.
     *
     * @param theRequest the request
     * @param theOnContent receives answer chunks
     * @param theOnThinking receives reasoning chunks
     * @param theThink whether the reasoning pass is requested, {@code null}
     *            leaves the field out of the body
     * @return the assembled answer
     * @throws LLMException when the endpoint is unreachable, rejects or
     *             returns an unexpected body
     */
    private ChatResult streamedRound(final ChatRequest theRequest,
        final Consumer<String> theOnContent, final Consumer<String> theOnThinking,
        final Boolean theThink) throws LLMException {
        final JsonObject body = buildBody(theRequest, true, theThink);
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
                final String thought = textOrNull(message, THINKING_CAPABILITY);
                if(thought != null && !thought.isEmpty()) {
                    thinking.append(thought);
                    theOnThinking.accept(thought);
                }
                final String chunk = textOrNull(message, CONTENT);
                if(chunk != null && !chunk.isEmpty()) {
                    content.append(chunk);
                    theOnContent.accept(chunk);
                }
                final JsonArray calls = message.getAsJsonArray(TOOL_CALLS);
                if(calls != null && !calls.isEmpty()) {
                    lastToolCalls.addAll(calls);
                }
            }
            if(event.has(MODEL)) {
                modelName[0] = textOrNull(event, MODEL);
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
        body.addProperty(MODEL, resolveModel(theModel));
        // buffered answer, one JSON document: the point is the load itself,
        // not the generation, so the request carries no prompt at all
        body.addProperty("stream", Boolean.FALSE);
        appendKeepAlive(body);
        appendWarmWindow(body);
        postJson("/api/generate", body, WARM_TIMEOUT_MS);
    }

    /**
     * Whether the endpoint model accepts the reasoning pass. The runtime
     * describes its own models, so that description answers first; a model
     * the endpoint rejected once is remembered and never asked again.
     *
     * @param theModel model name, {@code null} for the configured default
     * @return {@code true} when the reasoning pass may be requested
     */
    @Override
    public boolean supportsThinking(final String theModel) {
        final String model = modelKey(theModel);
        if(ourNoThinking.contains(model)) {
            return false;
        }
        return hasCapability(model, THINKING_CAPABILITY);
    }

    /**
     * Whether the endpoint describes the model as completing prompts. An
     * embedding-only model answers a different call and would fail every
     * chat round, so the description - not the model's name - decides
     * whether it is offered as a chat contact.
     *
     * @param theModel model name, {@code null} for the configured default
     * @return {@code false} when the endpoint describes the model as unable
     *         to complete prompts
     */
    @Override
    public boolean supportsChatCapability(final String theModel) {
        return hasCapability(modelKey(theModel), COMPLETION_CAPABILITY);
    }

    /**
     * @param theModel model name as this endpoint would call it
     * @param theCapability capability name to look for
     * @return {@code true} when the endpoint reports it or describes nothing
     *         (an endpoint that cannot describe its model is not evidence
     *         against it)
     */
    private boolean hasCapability(final String theModel, final String theCapability) {
        final Set<String> capabilities = capabilities(theModel);
        return capabilities.isEmpty() || capabilities.contains(theCapability);
    }

    /**
     * @param theModel model name as this endpoint would call it
     * @return capability names the endpoint reports for the model, empty when
     *         the endpoint does not describe models
     */
    private Set<String> capabilities(final String theModel) {
        final Set<String> cached = ourCapabilities.get(theModel);
        if(cached != null) {
            return cached;
        }
        final Set<String> fresh = readCapabilities(theModel);
        ourCapabilities.put(theModel, fresh);
        return fresh;
    }

    /**
     * Asks the endpoint once for what its model can do.
     *
     * @param theModel model name
     * @return immutable capability names, empty when nothing was reported
     */
    private Set<String> readCapabilities(final String theModel) {
        final JsonObject body = new JsonObject();
        body.addProperty(MODEL, theModel);
        try {
            final JsonObject answer = postJson("/api/show", body, PING_TIMEOUT_MS);
            final JsonArray reported = answer.getAsJsonArray("capabilities");
            if(reported == null || reported.isEmpty()) {
                return Set.of();
            }
            final Set<String> found = new HashSet<>();
            for(final JsonElement entry : reported) {
                found.add(entry.getAsString());
            }
            return Set.copyOf(found);
        } catch (final LLMException | RuntimeException ex) {
            // an endpoint that cannot describe its model is no reason to drop
            // the reasoning pass: the round itself stays the source of truth
            return Set.of();
        }
    }

    /**
     * Recognizes the endpoint's rejection of a reasoning request. The status
     * alone is not enough: an ordinary bad request must surface instead of
     * being retried, so the body has to name the thinking capability.
     *
     * @param theError failure of the round
     * @param theThink reasoning setting of the rejected round
     * @return {@code true} when one retry without the pass is worth it
     */
    private static boolean isThinkingRejected(final LLMException theError, final Boolean theThink) {
        if(theThink == null || !theThink.booleanValue() || theError.getStatusCode() != 400) {
            return false;
        }
        final String message = theError.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(THINKING_CAPABILITY);
    }

    /**
     * Remembers that this request's model has no reasoning pass.
     *
     * @param theRequest the rejected request
     */
    private void forgetThinking(final ChatRequest theRequest) {
        final String key = modelKey(theRequest.getModel());
        ourNoThinking.add(key);
        ColleagueLog.info("[colleague] model " + key
            + " has no thinking pass: the round is retried without it");
    }

    /**
     * @param theModel model name, {@code null} for the configured default
     * @return stable key of the model this endpoint would call
     */
    private String modelKey(final String theModel) {
        try {
            return resolveModel(theModel);
        } catch (final LLMException ex) {
            return String.valueOf(theModel);
        }
    }

    /**
     * Reasoning setting to actually send for this request. Once the endpoint
     * rejected the pass for the model, every later round of that model is
     * built without it, so the session pays the failed round-trip once.
     *
     * @param theRequest the request about to go out
     * @return the reasoning setting for the body, {@code null} leaves the
     *         field out
     */
    private Boolean effectiveThink(final ChatRequest theRequest) {
        final Boolean asked = theRequest.getThink();
        if(asked == null || !asked.booleanValue()) {
            return asked;
        }
        return ourNoThinking.contains(modelKey(theRequest.getModel()))
            ? Boolean.FALSE : asked;
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
     * An upper bound on what the message list costs the runtime's tokenizer,
     * by the shared character-length rule of {@link PromptTokens}.
     *
     * @param theMessages the message list going into this call
     * @return estimated token count
     */
    private static int estimatedPromptTokens(final JsonArray theMessages) {
        int characters = 0;
        for(final JsonElement entry : theMessages) {
            final String content = textOrNull(entry.getAsJsonObject(), CONTENT);
            if(content != null) {
                characters += content.length();
            }
        }
        return PromptTokens.estimateForCharacters(characters);
    }

    private JsonObject buildBody(final ChatRequest theRequest, final boolean theStreaming,
        final Boolean theThink) throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty(MODEL, resolveModel(theRequest.getModel()));
        body.addProperty("stream", Boolean.valueOf(theStreaming));
        appendKeepAlive(body);
        if(theThink != null) {
            body.addProperty("think", theThink.booleanValue());
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
                entry.add(TOOL_CALLS, calls);
                entry.addProperty(CONTENT, message.getContent());
            } else {
                entry.addProperty(CONTENT, message.getContent());
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
