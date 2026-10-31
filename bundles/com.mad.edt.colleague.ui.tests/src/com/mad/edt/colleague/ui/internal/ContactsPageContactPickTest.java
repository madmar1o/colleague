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

    @Test
    @DisplayName("a complete chip tap becomes a contact on that provider and model")
    void completeTapBecomesContact() {
        final JsonObject args = new JsonObject();
        args.addProperty("id", "srata");
        args.addProperty("model", "qwen3.8-flash");

        final ContactsPanel.Contact contact = ContactsPanel.contactFromArgs(args);

        Assertions.assertNotNull(contact);
        Assertions.assertEquals("srata", contact.getProviderId());
        Assertions.assertEquals("qwen3.8-flash", contact.getModel());
        Assertions.assertEquals("", contact.getAgentName());
        Assertions.assertEquals("qwen3.8-flash", contact.getDisplayName());
    }

    @Test
    @DisplayName("a call naming no model is rejected")
    void missingModelIsRejected() {
        final JsonObject args = new JsonObject();
        args.addProperty("id", "srata");

        Assertions.assertNull(ContactsPanel.contactFromArgs(args));
    }

    @Test
    @DisplayName("a call naming no provider is rejected")
    void missingProviderIsRejected() {
        final JsonObject args = new JsonObject();
        args.addProperty("model", "qwen3.8-flash");

        Assertions.assertNull(ContactsPanel.contactFromArgs(args));
    }
}
