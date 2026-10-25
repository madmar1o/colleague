package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * never the reasoning pass, the reasoning request flag following the model
 * name, and the history cap trimming from the oldest end.
 */
class ConversationTest {

    /** records every request and replays scripted results */
    private static final class ScriptedProvider implements ILLMProvider {

        private final ProviderConfig myConfig;
        private final List<ChatRequest> myRequests = new ArrayList<>();
        private ChatResult myNext = new ChatResult("a", "model", "stop", 7, 12);

        ScriptedProvider(final String theDefaultModel) {
            myConfig = ProviderConfig.builder("test", LLMProviderType.OLLAMA)
                .baseUrl("http://runtime.test")
                .defaultModel(theDefaultModel)
                .build();
        }

        void script(final ChatResult theResult) {
            myNext = theResult;
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
            final Consumer<String> theOnContent, final Consumer<String> theOnThinking) {
            myRequests.add(theRequest);
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
        final List<String> rendered = new ArrayList<>();
        for(final ChatMessage message : theRequest.getMessages()) {
            rendered.add(message.getRole() + '|' + message.getContent());
        }
        return rendered;
    }

    @Test
    void firstQuestionCarriesSystemPromptThenContextThenQuestion() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, "You are the colleague.");
        conversation.ask("plain-model", "file A.java", "hello");
        assertEquals(List.of(
            "system|You are the colleague.",
            "system|Workspace context:\nfile A.java",
            "user|hello"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void nextTurnReplaysHistoryBeforeTheFreshContext() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        provider.script(new ChatResult("first answer", "model", "stop", 1, 2));
        final Conversation conversation = new Conversation(provider, "prompt");
        conversation.ask("plain-model", "old context", "q1");
        conversation.ask("plain-model", "new context", "q2");
        // the context message of the finished turn joins the history and is
        // replayed with it, while the new turn's context sits right before
        // its question
        assertEquals(List.of(
            "system|prompt",
            "system|Workspace context:\nold context",
            "user|q1",
            "assistant|first answer",
            "system|Workspace context:\nnew context",
            "user|q2"),
            rolesAndContent(provider.requests().get(1)));
    }

    @Test
    void historyKeepsFinalAnswerNotTheReasoningPass() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        provider.script(new ChatResult("final answer", "secret chain of thought",
            "model", "stop", 1, 2, null));
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask("plain-model", null, "q1");
        conversation.ask("plain-model", null, "q2");
        final List<String> second = rolesAndContent(provider.requests().get(1));
        assertTrue(second.contains("assistant|final answer"));
        for(final String entry : second) {
            assertTrue(!entry.contains("secret chain of thought"), entry);
        }
    }

    @Test
    void reasoningModelsAreRequestedToStreamTheirThinking() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask("qwen3:4b", null, "q1");
        assertEquals(Boolean.TRUE, provider.requests().get(0).getThink());
        conversation.ask("some-other-model", null, "q2");
        assertEquals(Boolean.FALSE, provider.requests().get(1).getThink());
    }

    @Test
    void missingModelFallsBackToTheProviderDefault() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("qwen3:8b");
        final Conversation conversation = new Conversation(provider, null);
        conversation.ask(null, null, "q1");
        final ChatRequest sent = provider.requests().get(0);
        assertEquals("qwen3:8b", sent.getModel());
        // the default name decides the reasoning flag as well
        assertEquals(Boolean.TRUE, sent.getThink());
    }

    @Test
    void emptyPromptAndContextProduceNoSystemMessages() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, "");
        conversation.ask("plain-model", "", "just a question");
        assertEquals(List.of("user|just a question"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void replyCarriesUsageStatisticsAfterTheAnswer() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        provider.script(new ChatResult("the answer", "model", "stop", 7, 12));
        final Conversation conversation = new Conversation(provider, null);
        final String reply = conversation.ask("plain-model", null, "q");
        assertTrue(reply.startsWith("the answer"), reply);
        final String tail = reply.substring("the answer".length());
        assertTrue(Pattern.compile("\\R--- 7/12 tokens, \\d+ ms\\z").matcher(tail).find(), tail);
    }

    @Test
    void noteExchangeEntersTheMemoryOfTheNextRequest() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, null);
        conversation.noteExchange("agent question", "agent answer");
        conversation.ask("plain-model", null, "follow-up");
        assertEquals(List.of(
            "user|agent question",
            "assistant|agent answer",
            "user|follow-up"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void historyCapKeepsTheNewestMessages() {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, null);
        for(int index = 0; index < 30; index++) {
            conversation.noteExchange("q" + index, "a" + index);
        }
        // each append trims as it goes: of 30 pairs the oldest 10 disappear
        // and the newest 40 message entries, q10 through a29, survive
        assertEquals(40, conversation.snapshotHistory().size());
        assertEquals("q10", conversation.snapshotHistory().get(0).getContent());
        assertEquals("a29", conversation.snapshotHistory().get(39).getContent());
    }

    @Test
    void resetClearsTheMemory() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, "prompt");
        conversation.noteExchange("q1", "a1");
        conversation.reset();
        assertTrue(conversation.snapshotHistory().isEmpty());
        conversation.ask("plain-model", null, "q2");
        // the system prompt is not part of the history: reset only forgets
        // the accumulated conversation, the instructions stay
        assertEquals(List.of("system|prompt", "user|q2"),
            rolesAndContent(provider.requests().get(0)));
    }

    @Test
    void cancellationLatchTravelsInsideTheRequest() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        final Conversation conversation = new Conversation(provider, null);
        final CancellationFlag latch = new CancellationFlag();
        conversation.ask("plain-model", null, "q", chunk -> { }, chunk -> { }, latch);
        assertSame(latch, provider.requests().get(0).getCancellation());
        conversation.ask("plain-model", null, "q2");
        // without an explicit latch the request carries its own fresh one,
        // so provider code can always poll a cancellation without a null check
        final CancellationFlag implicit = provider.requests().get(1).getCancellation();
        assertTrue(implicit != null && !implicit.isCancelled());
    }

    @Test
    void streamedChunksFlowIntoBothConsumers() throws LLMException {
        final ScriptedProvider provider = new ScriptedProvider("base-model");
        provider.script(new ChatResult("body", "thinking-text", "model", "stop", 1, 2, null));
        final Conversation conversation = new Conversation(provider, null);
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        conversation.ask("plain-model", null, "q", content::append, thinking::append);
        assertEquals("body", content.toString());
        assertEquals("thinking-text", thinking.toString());
    }
}
