package com.mad.edt.colleague.ui.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.llm.ChatRequest;
import com.mad.edt.colleague.llm.ChatResult;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Pins the lifetime rule of chat sessions: a session captured the endpoint as
 * it was configured when the contact was first opened. While the saved
 * configuration of that provider does not move, reopening the contact must
 * reuse the conversation; once the user corrects the address (the case that
 * kept a fixed 404 after the URL was saved with the API path), the next open
 * must build a session against the new configuration instead of replaying the
 * stale one, and a tab still holding the stale session must be recognised as
 * out of date.
 */
class ChatSessionRegistryTest {

    private static final String GATEWAY_V1 = "http://127.0.0.1:8080/v1";
    private static final String PROMPT = "prompt";

    /** endpoint stand-in: only the configuration it captured matters here */
    private static final class FakeProvider implements ILLMProvider {

        private final ProviderConfig myConfig;

        FakeProvider(final ProviderConfig theConfig) {
            myConfig = theConfig;
        }

        @Override
        public String getId() {
            return myConfig.getId();
        }

        @Override
        public ProviderConfig getConfig() {
            return myConfig;
        }

        @Override
        public ChatResult chat(final ChatRequest theRequest) throws LLMException {
            throw new LLMException("not called in this test");
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

    private static ProviderConfig config(final String theBaseUrl, final String theKey) {
        return ProviderConfig.builder("srata", LLMProviderType.OPENAI_COMPATIBLE)
            .baseUrl(theBaseUrl)
            .apiKey(theKey)
            .build();
    }

    /** Same shape as ContactsPanel.Contact.getKey(): id, NUL, model. */
    private static final String CONTACT_KEY = "srata\u0000qwen3.8-flash-next-iq3_s";

    @Test
    void unchangedConfigurationKeepsTheConversation() {
        final ChatSessionRegistry registry = new ChatSessionRegistry();
        final ProviderConfig saved = config(GATEWAY_V1, "k");
        final Supplier<ChatSession> factory =
            () -> new ChatSession(new FakeProvider(saved), PROMPT);

        final ChatSession first = registry.sessionFor(CONTACT_KEY, saved, factory);
        final ChatSession again = registry.sessionFor(CONTACT_KEY, saved, factory);
        assertSame(first, again, "reopening a contact must not restart its history");
        assertTrue(registry.isCurrent(first, saved),
            "a session matching the saved configuration is current");
    }

    @Test
    void correctedAddressRebuildsTheSession() {
        final ChatSessionRegistry registry = new ChatSessionRegistry();
        final ProviderConfig stale = config("http://127.0.0.1:8080", "k");
        final ChatSession old = registry.sessionFor(CONTACT_KEY, stale,
            () -> new ChatSession(new FakeProvider(stale), PROMPT));

        final ProviderConfig fixed = config(GATEWAY_V1, "k");
        assertFalse(registry.isCurrent(old, fixed),
            "the open tab must be recognised as built for the old address");
        final ChatSession current = registry.sessionFor(CONTACT_KEY, fixed,
            () -> new ChatSession(new FakeProvider(fixed), PROMPT));

        assertNotSame(old, current,
            "a session built for the old address must not answer the corrected one");
    }

    @Test
    void keyChangeOfTheApiKeyAlsoRebuilds() {
        final ChatSessionRegistry registry = new ChatSessionRegistry();
        final ProviderConfig withOldKey = config(GATEWAY_V1, "old");
        final ChatSession old = registry.sessionFor(CONTACT_KEY, withOldKey,
            () -> new ChatSession(new FakeProvider(withOldKey), PROMPT));

        final ProviderConfig withNewKey = config(GATEWAY_V1, "new");
        final ChatSession current = registry.sessionFor(CONTACT_KEY, withNewKey,
            () -> new ChatSession(new FakeProvider(withNewKey), PROMPT));
        assertNotSame(old, current, "a rotated key is a different endpoint contract");
    }

    @Test
    void removedProviderIsNeverCurrent() {
        final ChatSessionRegistry registry = new ChatSessionRegistry();
        final ProviderConfig saved = config(GATEWAY_V1, "k");
        final ChatSession session = registry.sessionFor(CONTACT_KEY, saved,
            () -> new ChatSession(new FakeProvider(saved), PROMPT));

        assertFalse(registry.isCurrent(session, null),
            "a provider the user deleted leaves no live conversation behind");
    }
}
