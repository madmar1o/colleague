package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.ToolCall;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * ReAct-style loop: send conversation plus advertised tools to the model,
 * execute requested tool calls, feed results back and repeat until the model
 * answers without requesting further execution.
 */
public final class AgentLoop {

    /**
     * Round-trips one task gets by default. Twelve, not eight: a reasoning
     * local model spends two or three calls just to locate the files a task
     * names, and at eight the budget dies before the interesting half of the
     * work starts. The ceiling costs nothing early - a model that answers
     * soon pays only for the rounds it used.
     */
    private static final int DEFAULT_MAX_STEPS = 12;
    private static final int MAX_TOOL_RESULT_CHARS = 4_000;

    /**
     * Generation budget one round-trip gets: reasoning-capable local models
     * first "think" through the task and only then emit the tool call, so a
     * runtime default small enough to stop mid-reasoning loses the call in
     * silence and the loop mistakes the truncated turn for a plain answer.
     * The budget is a ceiling, not a target: a model that stops early pays
     * nothing for the unused part of it.
     */
    private static final int ROUND_TRIP_OUTPUT_TOKENS = 4_096;

    /**
     * Generation budget of the repair round. The round it repairs died with
     * the thinking pass eating the whole ceiling, so repeating that ceiling
     * would doom the repair to the same silent cut-off; the budget doubles
     * while the thinking pass is suppressed, so the extra room goes to the
     * answer itself.
     */
    private static final int REPAIR_ROUND_OUTPUT_TOKENS = 8_192;

    private final ILLMProvider provider;
    private final Map<String, IToolExecutor> executors;
    private final String model;
    private final int maxSteps;
    private final CancellationFlag cancellation;
    private final AgentObserver observer;

    /**
     * @param theProvider model endpoint
     * @param theTools callable tools, name-collisions keep the last one
     * @param theModel model to use, {@code null} for the provider default
     * @param theMaxSteps budget of model round-trips, {@code <= 0} for the default
     */
    public AgentLoop(final ILLMProvider theProvider, final List<IToolExecutor> theTools,
        final String theModel, final int theMaxSteps) {
        this(theProvider, theTools, theModel, theMaxSteps, new CancellationFlag());
    }

    /**
     * @param theProvider model endpoint
     * @param theTools callable tools, name-collisions keep the last one
     * @param theModel model to use, {@code null} for the provider default
     * @param theMaxSteps budget of model round-trips, {@code <= 0} for the default
     * @param theCancellation shared stop flag: the loop aborts between steps
     *            and every model round-trip carries it
     */
    public AgentLoop(final ILLMProvider theProvider, final List<IToolExecutor> theTools,
        final String theModel, final int theMaxSteps, final CancellationFlag theCancellation) {
        this(theProvider, theTools, theModel, theMaxSteps, theCancellation, AgentObserver.NOOP);
    }

    /**
     * @param theProvider model endpoint
     * @param theTools callable tools, name-collisions keep the last one
     * @param theModel model to use, {@code null} for the provider default
     * @param theMaxSteps budget of model round-trips, {@code <= 0} for the default
     * @param theCancellation shared stop flag: the loop aborts between steps
     *            and every model round-trip carries it
     * @param theObserver step trace listener, {@code null} for no listening
     */
    public AgentLoop(final ILLMProvider theProvider, final List<IToolExecutor> theTools,
        final String theModel, final int theMaxSteps, final CancellationFlag theCancellation,
        final AgentObserver theObserver) {
        provider = theProvider;
        executors = new LinkedHashMap<>();
        for(final IToolExecutor executor : theTools) {
            executors.put(executor.getName(), executor);
        }
        model = theModel;
        maxSteps = theMaxSteps > 0 ? theMaxSteps : DEFAULT_MAX_STEPS;
        cancellation = theCancellation == null ? new CancellationFlag() : theCancellation;
        observer = theObserver == null ? AgentObserver.NOOP : theObserver;
    }

    /**
     * Runs one task to completion.
     *
     * @param theSystemPrompt role and skill instructions, may be {@code null}
     * @param theUserMessage the task
     * @return the final answer
     * @throws LLMException when the model endpoint fails
     */
    public AgentAnswer run(final String theSystemPrompt, final String theUserMessage) throws LLMException {
        final List<ChatMessage> messages = new ArrayList<>();
        if(theSystemPrompt != null && !theSystemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(theSystemPrompt));
        }
        messages.add(ChatMessage.user(theUserMessage));
        return run(messages);
    }

    /**
     * Runs one task over an already open conversation: the loop continues the
     * history it is handed instead of starting a fresh one. A delegate that
     * resumes its parent's or its own earlier exchange uses this entry.
     *
     * @param theConversation messages to start from, system message first when
     *            the caller wants one; the loop appends to this list
     * @return the final answer
     * @throws LLMException when the model endpoint fails
     */
    public AgentAnswer run(final List<ChatMessage> theConversation) throws LLMException {
        final List<ChatMessage> messages = new ArrayList<>(theConversation);

        final List<ToolSpec> specs = new ArrayList<>();
        for(final IToolExecutor executor : executors.values()) {
            specs.add(executor.getSpec());
        }

        int steps = 0;
        int executed = 0;
        while(steps < maxSteps) {
            steps++;
            if(cancellation.isCancelled()) {
                return new AgentAnswer("", steps, executed, false);
            }
            final String roundModel = model != null ? model : defaultModel();
            final ChatRequest.Builder request = ChatRequest.builder(roundModel);
            for(final ChatMessage message : messages) {
                request.addMessage(message);
            }
            request.tools(specs);
            // the reasoning pass is where the tool-call syntax of local
            // reasoning models reliably appears; suppressing it on these
            // models makes them describe calls in prose instead of making
            // them. a model without the capability rejects the whole round,
            // so the provider - the side that learns from that rejection -
            // answers whether the pass may be requested at all
            request.think(provider.supportsThinking(roundModel));
            request.maxTokens(ROUND_TRIP_OUTPUT_TOKENS);
            request.cancellation(cancellation);

            ChatResult result;
            try {
                result = streamedRound(request.build());
            } catch (final LLMException ex) {
                // a local model server can lose only its generator process and
                // reload it on the next request: for that failure alone one
                // identical repair call is worth a full round-trip of the budget
                if(!ex.isTransient() || cancellation.isCancelled() || steps >= maxSteps) {
                    throw ex;
                }
                steps++;
                result = streamedRound(request.build());
            }
            if(isEmptyTurn(result) && steps < maxSteps) {
                // the round-trip ended with neither text nor a call: the
                // reasoning pass ate the generation budget before producing an
                // action. One repair asks the same task without thinking; if
                // that stays empty too, the model is not fixable this way and
                // chasing it further only burns the budget
                steps++;
                request.think(false);
                request.maxTokens(REPAIR_ROUND_OUTPUT_TOKENS);
                result = streamedRound(request.build());
            }
            if(!result.wantsToolCall()) {
                observer.onRound(steps, 0);
                return new AgentAnswer(finalText(result), steps, executed, true);
            }
            observer.onRound(steps, result.getToolCalls().size());

            messages.add(ChatMessage.assistantToolCalls(result.getContent(), result.getToolCalls()));
            executed += runToolRound(result.getToolCalls(), steps, messages);
        }
        // the budget died mid-work: one closing round-trip without tools
        // turns the executed-but-unanswered tool results into a summary the
        // user can read, instead of a bare exhaustion note. The interrupted
        // conversation travels with the answer: the caller can offer a
        // continuation over exactly this history, where the loop left off
        String closing = "";
        if(!cancellation.isCancelled()) {
            try {
                final ChatRequest.Builder wrap =
                    ChatRequest.builder(model != null ? model : defaultModel());
                for(final ChatMessage message : messages) {
                    wrap.addMessage(message);
                }
                wrap.think(false);
                wrap.maxTokens(Integer.valueOf(REPAIR_ROUND_OUTPUT_TOKENS));
                wrap.cancellation(cancellation);
                final ChatResult wrapResult = streamedRound(wrap.build());
                observer.onRound(steps + 1, 0);
                if(!wrapResult.wantsToolCall()) {
                    closing = finalText(wrapResult);
                }
            } catch (final LLMException ex) {
                // the summary is a bonus over the note: an endpoint failing
                // here must not lose the partial work already executed
            }
        }
        return new AgentAnswer(closing.isEmpty()
            ? "Step budget exhausted after " + steps + " round-trips" : closing,
            steps, executed, false, messages);
    }

    /**
     * One model round-trip over the streaming transport. A reasoning-heavy
     * turn of a local model can spend minutes generating its answer, longer
     * than any fixed budget for a whole buffered response: a streamed call
     * keeps the connection budget bound to the first frame only, so the
     * timeout fires on a dead endpoint instead of on a slow but living one.
     * Chunk consumers stay empty here: intermediate text of an agent turn is
     * not shown while tool calls are still being decided.
     *
     * @param theRequest the round-trip request
     * @return the round-trip result
     * @throws LLMException when the endpoint is unreachable, rejects or
     *             drops the call
     */
    private ChatResult streamedRound(final ChatRequest theRequest) throws LLMException {
        final Consumer<String> ignored = chunk -> { };
        return provider.chatStream(theRequest, ignored, ignored);
    }

    private String execute(final ToolCall theCall) {
        final IToolExecutor executor = executors.get(theCall.getName());
        if(executor == null) {
            return "ERROR: unknown tool '" + theCall.getName() + "'";
        }
        try {
            return executor.execute(theCall.getArgumentsJson());
        } catch (final Exception ex) {
            final String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return "ERROR: " + message;
        }
    }

    /**
     * Executes one round's tool calls and feeds the results back to the
     * conversation in call order.
     *
     * @param theCalls calls as the model emitted them
     * @param theStep round-trip number that requested them
     * @param theMessages conversation to append the tool results to
     * @return number of executed calls
     */
    private int runToolRound(final List<ToolCall> theCalls, final int theStep,
        final List<ChatMessage> theMessages) {
        if(canRunConcurrently(theCalls)) {
            return runConcurrently(theCalls, theStep, theMessages);
        }
        for(final ToolCall call : theCalls) {
            final long startedAt = System.nanoTime();
            final String toolResult = execute(call);
            observer.onToolCall(theStep, call.getName(), call.getArgumentsJson(), toolResult,
                (System.nanoTime() - startedAt) / 1_000_000);
            theMessages.add(ChatMessage.toolResult(call.getId(), truncate(toolResult)));
        }
        return theCalls.size();
    }

    /**
     * @param theCalls the round's tool calls
     * @return {@code true} when the round asks for more than one call and
     *         every one of them is a known side-effect-free tool, the only
     *         shape that may run at the same time; a single write-capable
     *         call keeps the whole round strictly serial
     */
    private boolean canRunConcurrently(final List<ToolCall> theCalls) {
        if(theCalls.size() < 2) {
            return false;
        }
        for(final ToolCall call : theCalls) {
            final IToolExecutor executor = executors.get(call.getName());
            if(executor == null || !executor.isParallelSafe()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs one round's calls side by side: several searches and file reads
     * observe no each other, so their latencies should not add up. Worker
     * threads stay daemon so a stuck tool can never keep the platform alive;
     * the round only ends once every result is in, and the trace plus the
     * conversation keep the original call order.
     *
     * @param theCalls the round's tool calls, all parallel-safe and known
     * @param theStep round-trip number that requested them
     * @param theMessages conversation to append the tool results to
     * @return number of executed calls
     */
    private int runConcurrently(final List<ToolCall> theCalls, final int theStep,
        final List<ChatMessage> theMessages) {
        final String[] outcome = new String[theCalls.size()];
        final long[] elapsedMs = new long[theCalls.size()];
        final List<Thread> workers = new ArrayList<>(theCalls.size());
        for(int index = 0; index < theCalls.size(); index++) {
            final int slot = index;
            final ToolCall call = theCalls.get(index);
            final Thread worker = new Thread(() -> {
                final long startedAt = System.nanoTime();
                outcome[slot] = execute(call);
                elapsedMs[slot] = (System.nanoTime() - startedAt) / 1_000_000;
            }, "colleague-tool-" + call.getName());
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        for(final Thread worker : workers) {
            try {
                worker.join();
            } catch (final InterruptedException ex) {
                // the turn is being stopped mid-round: unstarted or unfinished
                // slots still need a result message so the conversation shape
                // stays valid, and the cancellation flag ends the loop after
                Thread.currentThread().interrupt();
            }
        }
        for(int index = 0; index < theCalls.size(); index++) {
            final ToolCall call = theCalls.get(index);
            final String toolResult = outcome[index] == null
                ? "ERROR: interrupted before the result arrived" : outcome[index];
            observer.onToolCall(theStep, call.getName(), call.getArgumentsJson(), toolResult,
                elapsedMs[index]);
            theMessages.add(ChatMessage.toolResult(call.getId(), truncate(toolResult)));
        }
        return theCalls.size();
    }

    /**
     * @param theResult the round-trip result
     * @return {@code true} when the round produced neither answer text nor a
     *         tool call, the signature of a reasoning pass cut off mid-way
     */
    private static boolean isEmptyTurn(final ChatResult theResult) {
        return !theResult.wantsToolCall()
            && (theResult.getContent() == null || theResult.getContent().trim().isEmpty());
    }

    /**
     * Final answer text of a round-trip the model ended without a tool call.
     * A turn that produced neither text nor a call is the signature of a
     * reasoning pass cut off by the budget: hand the user a visible notice so
     * the failure is never mistaken for an intentionally brief answer.
     *
     * @param theResult the round-trip result
     * @return non-empty answer text
     */
    private static String finalText(final ChatResult theResult) {
        final String content = theResult.getContent();
        if(content != null && !content.trim().isEmpty()) {
            return content;
        }
        return "The model ended the round without an answer text or a tool call -"
            + " its reasoning pass was most likely cut off by the generation budget."
            + " Re-ask the question or narrow the task.";
    }

    private String defaultModel() {
        final String configured = provider.getConfig().getDefaultModel();
        if(configured == null || configured.isEmpty()) {
            return "default";
        }
        return configured;
    }

    private static String truncate(final String theText) {
        if(theText == null) {
            return "";
        }
        if(theText.length() <= MAX_TOOL_RESULT_CHARS) {
            return theText;
        }
        return theText.substring(0, MAX_TOOL_RESULT_CHARS) + "...[truncated]";
    }
}
