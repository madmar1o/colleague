package com.mad.edt.colleague.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final int DEFAULT_MAX_STEPS = 8;
    private static final int MAX_TOOL_RESULT_CHARS = 4_000;

    private final ILLMProvider provider;
    private final Map<String, IToolExecutor> executors;
    private final String model;
    private final int maxSteps;
    private final CancellationFlag cancellation;

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
        provider = theProvider;
        executors = new LinkedHashMap<>();
        for(final IToolExecutor executor : theTools) {
            executors.put(executor.getName(), executor);
        }
        model = theModel;
        maxSteps = theMaxSteps > 0 ? theMaxSteps : DEFAULT_MAX_STEPS;
        cancellation = theCancellation == null ? new CancellationFlag() : theCancellation;
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
            final ChatRequest.Builder request = ChatRequest.builder(
                model != null ? model : defaultModel());
            for(final ChatMessage message : messages) {
                request.addMessage(message);
            }
            request.tools(specs);
            request.cancellation(cancellation);

            final ChatResult result = provider.chat(request.build());
            if(!result.wantsToolCall()) {
                return new AgentAnswer(result.getContent(), steps, executed, true);
            }

            messages.add(ChatMessage.assistantToolCalls(result.getContent(), result.getToolCalls()));
            for(final ToolCall call : result.getToolCalls()) {
                final String toolResult = execute(call);
                executed++;
                messages.add(ChatMessage.toolResult(call.getId(), truncate(toolResult)));
            }
        }
        return new AgentAnswer("Step budget exhausted after " + steps + " round-trips",
            steps, executed, false);
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
