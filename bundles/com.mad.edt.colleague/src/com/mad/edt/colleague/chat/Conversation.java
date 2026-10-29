package com.mad.edt.colleague.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;

/**
 * Stateful conversation: keeps the message history and replays it with every
 * new question, attaching the per-turn workspace context and answering with
 * usage statistics appended. Runs on a background thread; not reentrant.
 * This is the conversation logic the chat UI drives, kept free of workbench
 * types so its rules can be pinned by direct tests.
 */
public final class Conversation {

    /**
     * How many messages the history holds before the oldest turns are
     * compacted into a digest. A hard cap on replayed history keeps prompt
     * cost predictable for a local model.
     */
    public static final int MAX_HISTORY_MESSAGES = 40;

    /**
     * First line of the digest message that replaces compacted turns: the
     * model is told outright that earlier conversation was summarized
     * instead of being handed the summary as if it were a live turn.
     */
    public static final String DIGEST_MARKER = "[earlier conversation compacted]";

    /**
     * How many most recent messages always stay verbatim: recent turns are
     * what follow-up questions are built on, they are never folded away.
     */
    private static final int KEEP_RECENT_MESSAGES = 24;

    /**
     * Ceiling for the digest body itself: an entire working day of repeated
     * compactions must not grow the summary back into a second full history.
     */
    private static final int DIGEST_MAX_CHARS = 1_800;

    /** How much of one compacted message reaches a digest line. */
    private static final int DIGEST_SNIPPET_CHARS = 120;

    private final ILLMProvider provider;
    private final String systemPrompt;
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * Whether the last finished turn was cut by the runtime's context
     * window; the chat panel reads it to label such an answer incomplete.
     */
    private boolean lastTurnTruncated;

    /**
     * @param theProvider endpoint to talk to
     * @param theSystemPrompt assistant role instructions, may be {@code null}
     */
    public Conversation(final ILLMProvider theProvider, final String theSystemPrompt) {
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
        return ask(theModel, theContext, theQuestion, chunk -> { });
    }

    /**
     * Asks the model with a live feed of generated text chunks.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta) throws LLMException {
        return ask(theModel, theContext, theQuestion, theOnDelta, chunk -> { });
    }

    /**
     * Asks the model with a live feed of generated text, keeping the model's
     * reasoning pass on a separate channel so the caller can present it apart
     * from the final answer.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @param theOnThinking receives reasoning chunks as they are generated
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta, final Consumer<String> theOnThinking)
        throws LLMException {
        return ask(theModel, theContext, theQuestion, theOnDelta, theOnThinking, null);
    }

    /**
     * Asks the model with the cancellation latch of the caller's turn wired
     * into the request: stopping the turn stops the streamed read loop.
     * When the endpoint dies in the middle of an answer, the question and
     * the text that reached the delta feed stay in the history, noted as cut
     * short; an endpoint that never produced anything leaves no trace.
     *
     * @param theModel model name, {@code null} for the provider default
     * @param theContext project/resource description, may be {@code null}
     * @param theQuestion question text
     * @param theOnDelta receives answer chunks as they are generated
     * @param theOnThinking receives reasoning chunks as they are generated
     * @param theCancellation shared stop flag, {@code null} for none
     * @return formatted reply (answer plus usage statistics)
     * @throws LLMException when the endpoint fails
     */
    public String ask(final String theModel, final String theContext, final String theQuestion,
        final Consumer<String> theOnDelta, final Consumer<String> theOnThinking,
        final CancellationFlag theCancellation) throws LLMException {
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
        request.think(Boolean.valueOf(wantsVisibleReasoning(theModel)));
        if(theCancellation != null) {
            request.cancellation(theCancellation);
        }

        final long startedAt = System.nanoTime();
        final StringBuilder streamedAnswer = new StringBuilder();
        final ChatResult result;
        try {
            result = provider.chatStream(request.build(), chunk -> {
                streamedAnswer.append(chunk);
                theOnDelta.accept(chunk);
            }, theOnThinking);
        } catch (final LLMException ex) {
            // an endpoint that dies mid-answer leaves the text already shown
            // to the user through the delta feed: the memory keeps the
            // question and that same text with a note, so the next turn
            // still knows what was said; an endpoint that produced nothing
            // leaves no trace: there is no half-said thing to remember
            if(streamedAnswer.length() > 0) {
                rememberExchange(contextMessage, theQuestion, streamedAnswer
                    + "\n[the answer was cut short: " + ex.getMessage() + "]");
            }
            throw ex;
        }
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        rememberExchange(contextMessage, theQuestion, result.getContent());
        lastTurnTruncated = result.truncated();

        return result.getContent()
            + String.format("%n--- %d/%d tokens, %d ms", Integer.valueOf(result.getPromptTokens()),
                Integer.valueOf(result.getCompletionTokens()), Long.valueOf(elapsedMs));
    }

    /**
     * @return {@code true} when the last finished turn ended because the
     *         runtime's context window ran out and cut the oldest prompt
     *         text away; the chat panel shows the user that the answer is
     *         only as complete as the surviving tail of the prompt
     */
    public boolean wasLastTurnTruncated() {
        return lastTurnTruncated;
    }

    /**
     * Puts one finished exchange - context message, question and answer -
     * into the memory and trims it, the way every completed turn does.
     *
     * @param theContextMessage turn's workspace context message, may be {@code null}
     * @param theQuestion user side of the exchange
     * @param theAnswer assistant side of the exchange
     */
    private void rememberExchange(final ChatMessage theContextMessage, final String theQuestion,
        final String theAnswer) {
        if(theContextMessage != null) {
            history.add(theContextMessage);
        }
        history.add(ChatMessage.user(theQuestion));
        history.add(ChatMessage.assistant(theAnswer));
        trimHistory();
    }

    /**
     * Discards the accumulated conversation.
     */
    public void reset() {
        history.clear();
    }

    /**
     * @return the endpoint this conversation talks to
     */
    public ILLMProvider getProvider() {
        return provider;
    }

    /**
     * @return assistant role instructions, may be {@code null}
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Appends an exchange produced outside {@link #ask} (for example by the
     * agent loop) so follow-up questions keep the memory of it.
     *
     * @param theQuestion user side of the exchange
     * @param theAnswer assistant side of the exchange
     */
    public void noteExchange(final String theQuestion, final String theAnswer) {
        history.add(ChatMessage.user(theQuestion));
        history.add(ChatMessage.assistant(theAnswer));
        trimHistory();
    }

    /**
     * @return a snapshot of the conversation so far, for re-rendering views
     */
    public List<ChatMessage> snapshotHistory() {
        return List.copyOf(history);
    }

    /**
     * Reasoning-capable models produce their chain of thought in a separate
     * stream field; the assistant history keeps only the final answer, so the
     * reasoning pass is requested and shown to the user collapsed apart from
     * the answer. The provider is the only side that knows the capability:
     * model names say nothing reliable about it.
     *
     * @param theModel model name, may be {@code null}
     * @return {@code true} when the reasoning pass must be requested
     */
    private boolean wantsVisibleReasoning(final String theModel) {
        final String name = theModel == null ? provider.getConfig().getDefaultModel() : theModel;
        return name != null && provider.supportsThinking(name);
    }

    /**
     * Folds the oldest turns that no longer fit the cap into one digest
     * message kept at the front: silently discarding them would leave the
     * model without any trace of what the conversation was about, while the
     * digest costs one short system message. Every later compaction merges
     * its share into that same digest so there is always exactly one, and
     * the most recent turns always stay verbatim.
     */
    private void trimHistory() {
        if(history.size() <= MAX_HISTORY_MESSAGES) {
            return;
        }
        final StringBuilder body = new StringBuilder();
        int from = 0;
        if(isDigest(history.get(0))) {
            // an earlier digest joins the fold: its notes carry over into
            // the new one instead of a second digest stacking up
            body.append(digestBody(history.get(0))).append('\n');
            from = 1;
        }
        final int foldUntil = history.size() - KEEP_RECENT_MESSAGES;
        for(int index = from; index < foldUntil; index++) {
            final ChatMessage message = history.get(index);
            final String label;
            if(ChatMessage.ROLE_USER.equals(message.getRole())) {
                label = "Q: ";
            } else if(ChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                label = "A: ";
            } else {
                // per-turn context notes are re-attached every turn anyway,
                // folding them into the digest would only repeat noise
                continue;
            }
            body.append(label).append(snippet(message.getContent())).append('\n');
        }
        // the old digest (index 0) is cleared with the fold too: its notes
        // already went into body, and re-inserting at index 0 keeps exactly
        // one digest at the front
        history.subList(0, foldUntil).clear();
        history.add(0, ChatMessage.system(DIGEST_MARKER + "\n"
            + ContextBudget.trimMiddle(body.toString().strip(), DIGEST_MAX_CHARS)));
    }

    private static boolean isDigest(final ChatMessage theMessage) {
        return ChatMessage.ROLE_SYSTEM.equals(theMessage.getRole())
            && theMessage.getContent().startsWith(DIGEST_MARKER);
    }

    private static String digestBody(final ChatMessage theDigest) {
        final int lineEnd = theDigest.getContent().indexOf('\n');
        return lineEnd < 0 ? "" : theDigest.getContent().substring(lineEnd + 1);
    }

    private static String snippet(final String theContent) {
        final String flattened = theContent.strip().replaceAll("\\s*[\r\n]+\\s*", " ");
        return flattened.length() <= DIGEST_SNIPPET_CHARS ? flattened
            : flattened.substring(0, DIGEST_SNIPPET_CHARS) + "...";
    }
}
