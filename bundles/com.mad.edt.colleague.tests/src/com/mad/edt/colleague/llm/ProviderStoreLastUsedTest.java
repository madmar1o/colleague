package com.mad.edt.colleague.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The workspace remembers which provider and model the user actually talked
 * to last: on a fresh start the default chat opens on that pair instead of
 * the provider's build-time default model. Reported live on 2026-11-05 —
 * a new session surfaced the old qwen default after the user had switched
 * models in the chat. This covers only the store: the UI wiring that calls
 * in and reads the pair is covered by the view-level tests.
 */
class ProviderStoreLastUsedTest {

    /** @return the preferences node the store writes into */
    private static org.osgi.service.prefs.Preferences node() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
            .getNode("com.mad.edt.colleague.llm");
    }

    /**
     * A remembered pair survives the call and is readable back; the write
     * must land in the same preferences node the settings use.
     */
    @Test
    void rememberLastUsedWritesReadablePair() {
        ProviderStore.rememberLastUsed("srata", "gemma4:e4b");
        assertEquals("srata", ProviderStore.lastUsedProvider());
        assertEquals("gemma4:e4b", ProviderStore.lastUsedModel());
        assertEquals("srata\u0000gemma4:e4b", node().get("last-used", ""));
    }

    /**
     * Incomplete input is not a user decision: a blank provider or model
     * keeps the previously remembered pair instead of erasing it.
     */
    @Test
    void blankInputIsIgnored() {
        ProviderStore.rememberLastUsed("srata", "gemma4:e4b");
        ProviderStore.rememberLastUsed("", "gemma4:e4b");
        assertEquals("srata", ProviderStore.lastUsedProvider());
        ProviderStore.rememberLastUsed("srata", "");
        assertEquals("gemma4:e4b", ProviderStore.lastUsedModel());
    }

    /**
     * A forged tab separator cannot smuggle an extra segment into the key:
     * values carrying it are ignored whole, again keeping the last good pair.
     */
    @Test
    void separatorInValueIsIgnored() {
        ProviderStore.rememberLastUsed("srata", "gemma4:e4b");
        ProviderStore.rememberLastUsed("bad\u0000open", "gemma4:e4b");
        assertEquals("srata", ProviderStore.lastUsedProvider());
    }

    /**
     * An unreadable stored value degrades to "no data" rather than throwing:
     * the pair form (provider, model separated by the tab separator) is the
     * only acceptable one — anything else reads as empty and the startup
     * path falls back to the provider's default model.
     */
    @Test
    void malformedStoredValueReadsAsEmpty() {
        node().put("last-used", "только-провайдер");
        assertEquals("", ProviderStore.lastUsedProvider());
        assertEquals("", ProviderStore.lastUsedModel());
    }
}
