package com.mad.edt.colleague.chat;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
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
}
