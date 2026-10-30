package com.mad.edt.colleague.chat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.commands.CommandDefinition;
import com.mad.edt.colleague.skills.SkillDefinition;
import com.mad.edt.colleague.skills.SkillRegistry;

/**
 * When a chat tab opens, the page receives one {@code catalog} event naming
 * everything the workspace offers: slash commands, project skills and agent
 * profiles. The page renders it as-is, so the payload must carry plain
 * name-plus-description entries and empty sections as empty arrays, never
 * missing fields.
 */
class PageCatalogEventsTest {

    @Test
    @DisplayName("the catalog event lists commands and skills with descriptions")
    void catalogCarriesCommandsAndSkills() {
        final SkillRegistry skills = new SkillRegistry();
        skills.register(new SkillDefinition("reporter", "print a report", "body", List.of()));
        skills.register(new SkillDefinition("quiet", "hidden helper", "body", List.of(),
            null, false));

        final JsonObject payload = PageCatalogEvents.build(
            List.of(new CommandDefinition("review", "review a change", "t", null, false),
                new CommandDefinition("standup", "", "t", null, false)),
            skills);

        Assertions.assertEquals(2, payload.getAsJsonArray("commands").size());
        final JsonObject first = payload.getAsJsonArray("commands").get(0).getAsJsonObject();
        Assertions.assertEquals("review", first.get("name").getAsString());
        Assertions.assertEquals("review a change", first.get("description").getAsString());

        Assertions.assertEquals(2, payload.getAsJsonArray("skills").size());
        Assertions.assertEquals("reporter",
            payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString());

        // profiles arrive with a later milestone: the page must be able to
        // read the field today, as an empty array rather than a gap
        Assertions.assertTrue(payload.getAsJsonArray("profiles").isEmpty());
    }

    @Test
    @DisplayName("a workspace with nothing installed still gets a well-formed catalog")
    void emptySectionsAreEmptyArrays() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry());

        Assertions.assertTrue(payload.getAsJsonArray("commands").isEmpty());
        Assertions.assertTrue(payload.getAsJsonArray("skills").isEmpty());
        Assertions.assertTrue(payload.getAsJsonArray("profiles").isEmpty());
    }

    @Test
    @DisplayName("profiles join the catalog as soon as the workspace has them")
    void profilesAreListedAndHiddenOnesStayOut() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(),
            List.of(new AgentProfile("reviewer", "review only", "system", null, 4,
                    AgentProfile.Mode.PRIMARY, Set.of("read_file"), false),
                new AgentProfile("internal", "not for the chip", "system", null, 0,
                    AgentProfile.Mode.ALL, Set.of(), true)));

        final JsonArray profiles = payload.getAsJsonArray("profiles");
        Assertions.assertEquals(1, profiles.size(),
            "a hidden profile is not something the user picks from a chip");
        final JsonObject profile = profiles.get(0).getAsJsonObject();
        Assertions.assertEquals("reviewer", profile.get("name").getAsString());
        Assertions.assertEquals("review only", profile.get("description").getAsString());
    }

    @Test
    @DisplayName("the contacts rail leads with the default colleague and keeps chat profiles")
    void contactsLeadWithDefaultAndKeepChatProfiles() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(),
            List.of(new AgentProfile("reviewer", "review only", "system", null, 4,
                    AgentProfile.Mode.PRIMARY, Set.of(), false),
                new AgentProfile("helper", "delegate worker", "system", null, 0,
                    AgentProfile.Mode.ALL, Set.of(), false),
                new AgentProfile("delegated", "sub-agent only", "system", null, 0,
                    AgentProfile.Mode.SUBAGENT, Set.of(), false),
                new AgentProfile("invisible", "not for the rail", "system", null, 0,
                    AgentProfile.Mode.ALL, Set.of(), true)));

        final JsonArray contacts = payload.getAsJsonArray("contacts");
        Assertions.assertEquals(3, contacts.size(),
            "default plus the two profiles allowed to run as the chat itself");
        final JsonObject builtIn = contacts.get(0).getAsJsonObject();
        Assertions.assertEquals("", builtIn.get("name").getAsString(),
            "the built-in colleague is picked by the empty profile name");
        Assertions.assertEquals("Колега по умолчанию", builtIn.get("label").getAsString());
        Assertions.assertEquals("default", builtIn.get("mode").getAsString());
        final JsonObject reviewer = contacts.get(1).getAsJsonObject();
        Assertions.assertEquals("reviewer", reviewer.get("name").getAsString());
        Assertions.assertEquals("review only", reviewer.get("description").getAsString());
        Assertions.assertEquals("primary", reviewer.get("mode").getAsString());
        Assertions.assertEquals("helper", contacts.get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    @DisplayName("with no workspace profiles the rail still offers the default contact")
    void contactsAlwaysCarryTheDefault() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(), null);
        final JsonArray contacts = payload.getAsJsonArray("contacts");
        Assertions.assertEquals(1, contacts.size());
        Assertions.assertEquals("", contacts.get(0).getAsJsonObject().get("name").getAsString());
    }
}
