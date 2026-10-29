package com.mad.edt.colleague.ui.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Keeps one conversation session per contact (provider plus model key) and
 * governs its lifetime. A session captured the endpoint as it was configured
 * when the contact was first opened; while the saved configuration of that
 * provider does not move, reopening the contact must reuse the conversation,
 * but once the user corrects the address, key or default model, the stale
 * session must never answer again - the corrected configuration has to take
 * effect instead of replaying the one that produced the old failure.
 */
public final class ChatSessionRegistry {

    private final Map<String, ChatSession> ourSessions = new HashMap<>();

    /**
     * @param theKey contact key: provider id, NUL, model name
     * @param theCurrent configuration the provider has right now,
     *            {@code null} when the provider was removed - a removed
     *            provider leaves its cached session untouched here, the view
     *            retires such tabs through {@link #isCurrent}
     * @param theFactory builds a session against the current configuration
     * @return the cached session while the configuration is unchanged, a
     *         freshly built one after the configuration moved
     */
    public ChatSession sessionFor(final String theKey, final ProviderConfig theCurrent,
        final Supplier<ChatSession> theFactory) {
        final ChatSession cached = ourSessions.get(theKey);
        if(cached != null
            && (theCurrent == null || sameEndpoint(cached.getProvider().getConfig(), theCurrent))) {
            return cached;
        }
        final ChatSession fresh = theFactory.get();
        ourSessions.put(theKey, fresh);
        return fresh;
    }

    /**
     * Whether a live session still matches the saved configuration of its
     * provider. A tab holding a stale session keeps talking to an endpoint
     * the user has corrected or removed, so the view closes it.
     *
     * @param theSession session a tab is using
     * @param theCurrent saved configuration of that provider, {@code null}
     *            when the provider was removed
     * @return false when the session must be retired
     */
    public boolean isCurrent(final ChatSession theSession, final ProviderConfig theCurrent) {
        return theCurrent != null
            && sameEndpoint(theSession.getProvider().getConfig(), theCurrent);
    }

    private static boolean sameEndpoint(final ProviderConfig theCached,
        final ProviderConfig theCurrent) {
        return theCached.getType() == theCurrent.getType()
            && theCached.getBaseUrl().equals(theCurrent.getBaseUrl())
            && Objects.equals(theCached.getApiKey(), theCurrent.getApiKey())
            && Objects.equals(theCached.getDefaultModel(), theCurrent.getDefaultModel());
    }
}
