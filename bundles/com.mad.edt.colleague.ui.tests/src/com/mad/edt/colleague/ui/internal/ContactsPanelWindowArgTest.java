package com.mad.edt.colleague.ui.internal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Pins how the provider form turns its window field into a token count:
 * absent or blank keeps the type default, a valid number travels through,
 * and an out-of-range value surfaces as the validation complaint of the
 * core - the page must never see a half-applied provider.
 */
class ContactsPanelWindowArgTest {

    @Test
    @DisplayName("a form that sent no window field means the type default")
    void absentFieldMeansDefault() {
        Assertions.assertEquals(0, ContactsPanel.windowFromArgs(new JsonObject()));
        final JsonObject blank = new JsonObject();
        blank.addProperty("window", "");
        Assertions.assertEquals(0, ContactsPanel.windowFromArgs(blank));
    }

    @Test
    @DisplayName("a valid window number travels through to the core parser")
    void validWindowPassesThrough() {
        final JsonObject args = new JsonObject();
        args.addProperty("window", "32768");
        Assertions.assertEquals(32_768, ContactsPanel.windowFromArgs(args));
    }

    @Test
    @DisplayName("an out-of-range window raises the core complaint, not a silent zero")
    void outOfRangePropagatesTheComplaint() {
        final JsonObject args = new JsonObject();
        args.addProperty("window", "500");
        final IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
            () -> ContactsPanel.windowFromArgs(args));
        Assertions.assertFalse(ex.getMessage().isEmpty(),
            "the message is what the form shows the user");
    }
}
