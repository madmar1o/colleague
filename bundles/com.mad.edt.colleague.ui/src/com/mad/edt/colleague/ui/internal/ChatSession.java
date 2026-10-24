package com.mad.edt.colleague.ui.internal;

import java.util.ArrayList;
import java.util.List;

import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;

/**
 * Stateful chat session: keeps the conversation history and sends it with
 * every new question. Runs on a background thread; not reentrant.
 */
public final class ChatSession {

    private static final int MAX_HISTORY_MESSAGES = 40;

    private final ILLMProvider provider;
    private final String systemPrompt;
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * @param theProvider endpoint to talk to
     * @param theSystemPrompt assistant role instructions, may be {@code null}
     */
    public ChatSession(final ILLMProvider theProvider, final String theSystemPrompt) {
        provider = theProvider;
        systemPrompt = theSystemPrompt;
    }

    /**
     * Asks the model one question with surrounding project context attached,
     * and appends both sides to the history.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion)
        throws LLMException {
        final ChatRequest.Builder request = ChatRequest
            .builder(theModel != null ? theModel : provider.getConfig().getDefaultModel());
        if(systemPrompt != null && !systemPrompt.isEmpty()) {
            request.addMessage(ChatMessage.system(systemPrompt));
        }
        final ChatMessage contextMessage = theContext != null && !theContext.isEmpty()
            ? ChatMessage.system("Workspace context:\n" + theContext) : null;
        for(final ChatMessage message : history) {
            request.addMessage(message);
        }
        if(contextMessage != null) {
            request.addMessage(contextMessage);
        }
        request.addMessage(ChatMessage.user(theQuestion));
        request.think(usesReasoningTemplate(theModel));

        final long startedAt = System.nanoTime();
        final ChatResult result = provider.chat(request.build());
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        if(contextMessage != null) {
            history.add(contextMessage);
        }
        history.add(ChatMessage.user(theQuestion));
        history.add(ChatMessage.assistant(result.getContent()));
        trimHistory();

        return result.getContent()
            + String.format("%n--- %d/%d tokens, %d ms", result.getPromptTokens(),
                result.getCompletionTokens(), Long.valueOf(elapsedMs));
    }

    /**
     * Discards the accumulated conversation.
     */
    public void reset() {
        history.clear();
    }

    /**
     * Reasoning-capable models stream their chain of thought into a separate
     * field and can exhaust the answer budget there, so the direct answer is
     * requested for known templates.
     *
     * @param theModel model name, may be {@code null}
     * @return {@code false} when the reasoning pass must be suppressed
     */
    private boolean usesReasoningTemplate(final String theModel) {
        final String name = theModel == null ? provider.getConfig().getDefaultModel() : theModel;
        return name != null && !name.contains("qwen3");
    }

    private void trimHistory() {
        while(history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
    }
}
