package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Pins how a conversation builds each request and grows its memory: system
 * instructions first, replayed history, the per-turn workspace context right
 * before the question, the assistant history keeping only final answers and
 * never the reasoning pass, the reasoning request flag following what the
 * provider reports about the model, and the history cap trimming from the
 * oldest end.
 */
class ConversationTest {

    // fixture vocabulary: the scripted endpoint always answers under this
    // model name, and the questions below are the ones the history asserts on
    private static final String MODEL = "model";
    private static final String BASE_MODEL = "base-model";
    private static final String PLAIN_MODEL = "plain-model";
    private static final String NEW_CONTEXT = "new context";
    private static final String CONTEXT_NEW = "system|Workspace context:\nnew context";
    private static final String USER_Q1 = "user|q1";
    private static final String USER_Q2 = "user|q2";
    private static final String THE_ANSWER = "the answer";

    /** records every request and replays scripted results */
    private static final class ScriptedProvider implements ILLMProvider {

        private final ProviderConfig myConfig;
        private final List<ChatRequest> myRequests = new ArrayList<>();
        private ChatResult myNext = new ChatResult("a", MODEL, "stop", 7, 12);
        private List<String> myStreamChunks = List.of();
        private LLMException myStreamFailure;
        private boolean myThinking = true;

        ScriptedProvider(final String theDefaultModel) {
            myConfig = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test")
                .defaultModel(theDefaultModel)
                .build();
        }

        /**
         * @param theThinking what the provider reports about its models
         */
        void thinking(final boolean theThinking) {
            myThinking = theThinking;
        }

        @Override
        public boolean supportsThinking(final String theModel) {
            return myThinking;
        }

        void script(final ChatResult theResult) {
            myNext = theResult;
        }

        /**
         * Makes the next streamed call hand over these answer chunks and
         * then die with the given error, the way an endpoint that drops the
         * connection in mid-answer does.
         *
         * @param theChunks chunks handed to the content consumer in order
         * @param theFailure error thrown after the chunks, {@code null} for none
         */
        void scriptStream(final List<String> theChunks, final LLMException theFailure) {
            myStreamChunks = theChunks;
            myStreamFailure = theFailure;
        }

        List<ChatRequest> requests() {
            return myRequests;
        }

        @Override
        public String getId() {
            return "test";
        }

        @Override
        public ProviderConfig getConfig() {
            return myConfig;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) {
            myRequests.add(theRequest);
            return myNext;
        }

        @Override
        public ChatResult chatStream(final ChatRequest theRequest,
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking)
            throws LLMException {
            myRequests.add(theRequest);
            if(!myStreamChunks.isEmpty() || myStreamFailure != null) {
                // the scripted break happens exactly once: the next call
                // behaves like a healthy endpoint again
                final List<String> chunks = myStreamChunks;
                final LLMException failure = myStreamFailure;
                myStreamChunks = List.of();
                myStreamFailure = null;
                chunks.forEach(theOnContent);
                if(failure != null) {
                    throw failure;
                }
            }
            if(!myNext.getContent().isEmpty()) {
                theOnContent.accept(myNext.getContent());
            }
            if(myNext.getThinking() != null) {
                theOnThinking.accept(myNext.getThinking());
            }
            return myNext;
        }

        @Override
        public List<String> listModels() {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static List<String> rolesAndContent(final ChatRequest theRequest) {
        return rolesAndContent(theRequest.getMessages());
    }

    private static List<String> contents(final List<ChatMessage> theMessages) {
        return rolesAndContent(theMessages);
    }

    private static List<String> rolesAndContent(final List<ChatMessage> theMessages) {
        final List<String> rendered = new ArrayList<>();
        for(final ChatMessage message : theMessages) {
            rendered.add(message.getRole() + '|' + message.getContent());
        }
        return rendered;
    }

    @Test
    void firstQuestionCarriesSystemPromptThenContextThenQuestion() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, "You are the colleague.");
        conversation.ask(PLAIN_MODEL, "file A.java", "hello");
        assertEquals(List.of(
            "system|You are the colleague.",
            "system|Workspace context:\nfile A.java",
            "user|hello"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void nextTurnReplaysHistoryBeforeTheFreshContext() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.script(new ChatResult("first answer", MODEL, "stop", 1, 2));
        final Conversation conversation = new Conversation(provider, "prompt");
        conversation.ask(PLAIN_MODEL, "old context", "q1");
        conversation.ask(PLAIN_MODEL, NEW_CONTEXT, "q2");
        // the context message of the finished turn joins the history and is
        // replayed with it, while the new turn's context sits right before
        // its question
        assertEquals(List.of(
            "system|prompt",
            "system|Workspace context:\nold context",
            USER_Q1,
            "assistant|first answer",
            CONTEXT_NEW,
            USER_Q2),
            rolesAndContent(provider.requests().get(1)));
    }

    @Test
    void historyKeepsFinalAnswerNotTheReasoningPass() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.script(new ChatResult("final answer", "secret chain of thought",
            MODEL, "stop", 1, 2, null));
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask(PLAIN_MODEL, null, "q1");
        conversation.ask(PLAIN_MODEL, null, "q2");
        final List<String> second = rolesAndContent(provider.requests().get(1));
        assertTrue(second.contains("assistant|final answer"));
        for(final String entry : second) {
            assertTrue(!entry.contains("secret chain of thought"), entry);
        }
    }

    @Test
    void thinkingIsRequestedOnlyWhereTheProviderReportsTheCapability() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask("thinking-model", null, "q1");
        assertEquals(Boolean.TRUE, provider.requests().get(0).getThink());
        provider.thinking(false);
        conversation.ask(PLAIN_MODEL, null, "q2");
        assertEquals(Boolean.FALSE, provider.requests().get(1).getThink());
    }

    @Test
    void missingModelFallsBackToTheProviderDefault() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("saved-default-model");
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask(null, null, "q1");
        final ChatRequest sent = provider.requests().get(0);
        assertEquals("saved-default-model", sent.getModel());
        // the default name decides the reasoning flag as well
        assertEquals(Boolean.TRUE, sent.getThink());
    }

    @Test
    void aProviderWithoutASavedModelRefusesToSendAGuess() {
        final ScriptedProvider provider = new ScriptedProvider(null);
        final Conversation conversation = new Conversation(provider, null);
        final LLMException error =
            assertThrows(LLMException.class, () -> conversation.ask(null, null, "q1"));
        assertTrue(error.getMessage().contains("test"),
            "the message must point at the provider whose settings need a model: "
                + error.getMessage());
        assertTrue(provider.requests().isEmpty(),
            "a request with a made-up model name must never leave for the endpoint");
    }

    @Test
    void emptyPromptAndContextProduceNoSystemMessages() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, "");
        conversation.ask(PLAIN_MODEL, "", "just a question");
        assertEquals(List.of("user|just a question"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void replyCarriesUsageStatisticsAfterTheAnswer() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.script(new ChatResult(THE_ANSWER, MODEL, "stop", 7, 12));
        final Conversation conversation = new Conversation(provider, null);
        final String reply = conversation.ask(PLAIN_MODEL, null, "q");
        assertTrue(reply.startsWith(THE_ANSWER), reply);
        final String tail = reply.substring(THE_ANSWER.length());
        assertTrue(Pattern.compile("\\R--- 7/12 tokens, \\d+ ms\\z").matcher(tail).find(), tail);
    }

    @Test
    void noteExchangeEntersTheMemoryOfTheNextRequest() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, null);
        conversation.noteExchange("agent question", "agent answer");
        conversation.ask(PLAIN_MODEL, null, "follow-up");
        assertEquals(List.of(
            "user|agent question",
            "assistant|agent answer",
            "user|follow-up"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void historyCapCompactsTheOldestMessages() {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, null);
        for(int index = 0; index < 30; index++) {
            conversation.noteExchange("q" + index, "a" + index);
        }
        // each append compacts as it goes: the oldest turns fold into one
        // digest at the front instead of vanishing, and the newest turns
        // stay verbatim at the tail
        assertEquals(27, conversation.snapshotHistory().size());
        assertTrue(conversation.snapshotHistory().get(0).getContent()
            .startsWith(Conversation.DIGEST_MARKER));
        assertEquals("q17", conversation.snapshotHistory().get(1).getContent());
        assertEquals("a29", conversation.snapshotHistory().get(26).getContent());
    }

    @Test
    void resetClearsTheMemory() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, "prompt");
        conversation.noteExchange("q1", "a1");
        conversation.reset();
        assertTrue(conversation.snapshotHistory().isEmpty());
        conversation.ask(PLAIN_MODEL, null, "q2");
        // the system prompt is not part of the history: reset only forgets
        // the accumulated conversation, the instructions stay
        assertEquals(List.of("system|prompt", USER_Q2),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void cancellationLatchTravelsInsideTheRequest() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, null);
        final CancellationFlag latch = new CancellationFlag();
        conversation.ask(PLAIN_MODEL, null, "q", chunk -> { }, chunk -> { }, latch);
        assertSame(latch, provider.requests().get(0).getCancellation());
        conversation.ask(PLAIN_MODEL, null, "q2");
        // without an explicit latch the request carries its own fresh one,
        // so provider code can always poll a cancellation without a null check
        final CancellationFlag implicit = provider.requests().get(1).getCancellation();
        assertTrue(implicit != null && !implicit.isCancelled());
    }

    @Test
    void streamedChunksFlowIntoBothConsumers() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.script(new ChatResult("body", "thinking-text", MODEL, "stop", 1, 2, null));
        final Conversation conversation = new Conversation(provider, null);
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        conversation.ask(PLAIN_MODEL, null, "q", content::append, thinking::append);
        assertEquals("body", content.toString());
        assertEquals("thinking-text", thinking.toString());
    }

    @Test
    void droppedAnswerKeepsTheQuestionAndItsTextInMemory() {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.scriptStream(List.of("first half", ", second half"),
            new LLMException("stream ended too early"));
        final Conversation conversation = new Conversation(provider, null);
        assertThrows(LLMException.class, () -> conversation.ask(PLAIN_MODEL, null, "q1"));
        // the half an answer already reached the user through the delta feed,
        // so the memory must carry the question and the same text: losing
        // them makes the next turn forget what was already shown
        assertEquals(List.of(USER_Q1, "assistant|first half, second half\n"
            + "[the answer was cut short: stream ended too early]"),
            contents(conversation.snapshotHistory()));
    }

    @Test
    void droppedAnswerContextJoinsTheMemoryOfTheNextTurn() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.scriptStream(List.of("partial"), new LLMException("connection reset"));
        final Conversation conversation = new Conversation(provider, null);
        assertThrows(LLMException.class, () -> conversation.ask(PLAIN_MODEL, "file B", "q1"));
        conversation.ask(PLAIN_MODEL, NEW_CONTEXT, "q2");
        // just like a successful turn, its workspace context message enters
        // the memory and is replayed before the recovered exchange
        assertEquals(List.of(
            "system|Workspace context:\nfile B",
            USER_Q1,
            "assistant|partial\n[the answer was cut short: connection reset]",
            CONTEXT_NEW,
            USER_Q2),
            rolesAndContent(provider.requests().get(1)));
    }

    @Test
    void endpointThatNeverAnsweredLeavesNoTraceInTheMemory() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        provider.scriptStream(List.of(), new LLMException("cannot reach the runtime"));
        final Conversation conversation = new Conversation(provider, null);
        assertThrows(LLMException.class, () -> conversation.ask(PLAIN_MODEL, "ctx", "q1"));
        // nothing was produced for the user, so the next request replays an
        // empty memory: pretending a half-empty exchange happened would
        // steer the model through a question it never answered
        assertTrue(conversation.snapshotHistory().isEmpty());
        conversation.ask(PLAIN_MODEL, NEW_CONTEXT, "q2");
        assertEquals(List.of(
            CONTEXT_NEW,
            USER_Q2),
            rolesAndContent(provider.requests().get(1)));
    }

    @Test
    void truncationFromTheRuntimeReachesTheUiSide() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider(BASE_MODEL);
        final Conversation conversation = new Conversation(provider, null);
        // the runtime says the window ran out: the chat panel reads the flag
        // off the conversation after the call and says the answer is cut
        provider.script(new ChatResult("half", MODEL, "length", 900, 40));
        conversation.ask(PLAIN_MODEL, "ctx", "q1");
        assertTrue(conversation.wasLastTurnTruncated());
        provider.script(new ChatResult("whole", MODEL, "stop", 9, 4));
        conversation.ask(PLAIN_MODEL, "ctx", "q2");
        assertEquals(false, Boolean.valueOf(conversation.wasLastTurnTruncated()),
            "a normally finished turn clears the flag");
    }
}
