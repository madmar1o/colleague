package com.mad.edt.colleague.ui.internal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * A tap on a model chip of the providers page sends one {@code openContact}
 * call carrying the provider id and the model name. The panel turns those
 * arguments into a contact it can open a chat on; when either half is missing
 * the call turns into nothing, because a chat on an empty model name would
 * only fail at the endpoint and leave a tab named after nothing.
 */
class ContactsPageContactPickTest {

    private static final String SRATA = "srata";
    private static final String QWEN = "qwen3.8-flash";

    @Test
    @DisplayName("a complete chip tap becomes a contact on that provider and model")
    void completeTapBecomesContact() {
        final JsonObject args = new JsonObject();
        args.addProperty("id", SRATA);
        args.addProperty("model", QWEN);

        final ContactsPanel.Contact contact = ContactsPanel.contactFromArgs(args);

        Assertions.assertNotNull(contact);
        Assertions.assertEquals(SRATA, contact.getProviderId());
        Assertions.assertEquals(QWEN, contact.getModel());
        Assertions.assertEquals("", contact.getAgentName());
        Assertions.assertEquals(QWEN, contact.getDisplayName());
    }

    @Test
    @DisplayName("a call naming no model is rejected")
    void missingModelIsRejected() {
        final JsonObject args = new JsonObject();
        args.addProperty("id", SRATA);

        Assertions.assertNull(ContactsPanel.contactFromArgs(args));
    }

    @Test
    @DisplayName("a call naming no provider is rejected")
    void missingProviderIsRejected() {
        final JsonObject args = new JsonObject();
        args.addProperty("model", QWEN);

        Assertions.assertNull(ContactsPanel.contactFromArgs(args));
    }
}
