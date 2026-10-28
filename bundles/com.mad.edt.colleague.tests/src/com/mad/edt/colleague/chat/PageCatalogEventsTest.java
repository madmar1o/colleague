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
}
