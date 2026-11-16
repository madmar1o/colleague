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

    /** payload sections the catalog event fills */
    private static final String COMMANDS = "commands";
    private static final String SKILLS = "skills";
    private static final String PROFILES = "profiles";
    /** entry fields the page reads out of the payload */
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String LABEL = "label";
    private static final String MODE = "mode";
    private static final String MODEL = "model";
    private static final String BUILTIN = "builtin";
    private static final String TITLE = "title";
    private static final String MAX_STEPS = "maxSteps";
    private static final String TOOLS = "tools";
    /** the rail section the page reads contacts out of */
    private static final String CONTACTS = "contacts";
    /** fixture profile names and values reused across tests */
    private static final String REVIEWER = "reviewer";
    private static final String REVIEW_ONLY = "review only";
    private static final String SYSTEM = "system";
    private static final String SUBAGENT_ONLY = "sub-agent only";
    private static final String DEFAULT_LABEL = "Колега по умолчанию";
    private static final String ANALYST = "analyst";
    private static final String ANALYST_TITLE = "Аналитик 1С";
    /** built-in contact and built-in card carry an empty name and model */
    private static final String BUILT_IN = "";
    /** contact rail modes the page distinguishes */
    private static final String DEFAULT_MODE = "default";
    private static final String PRIMARY_MODE = "primary";
    /** profiles the workspace owns and must keep out of the rail */
    private static final String HELPER = "helper";
    private static final String DELEGATED = "delegated";
    private static final String INVISIBLE = "invisible";
    private static final String READ_FILE = "read_file";
    private static final String SEARCH_CODE = "search_code";
    /** model the analyst profile pins */
    private static final String ANALYST_MODEL = "qwen3:8b";
    /** skill the workspace has installed in the first fixture */
    private static final String REPORTER = "reporter";
    /** profile hidden from the chip and from the page */
    private static final String INTERNAL = "internal";
    private static final String NOT_FOR_CHIP = "not for the chip";
    private static final String NOT_FOR_RAIL = "not for the rail";
    private static final String NOT_FOR_PAGE = "not for the page";
    /** description the helper profile carries on the rail */
    private static final String DELEGATE_WORKER = "delegate worker";
    /** analyst fixture reused by the agents page tests */
    private static final String ANALYST_ID = "analyst-1c";
    private static final String ANALYST_DESCRIPTION = "аналитик типовых решений";
    private static final String ANALYST_BRIEF = "разбор типовых";
    private static final String SYSTEM_PROMPT = "Ты — аналитик 1С.";
    /** tools listed in the analyst card and joined for the page */
    private static final String TOOLS_LIST = READ_FILE + ", " + SEARCH_CODE;

    @Test
    @DisplayName("the catalog event lists commands and skills with descriptions")
    void catalogCarriesCommandsAndSkills() {
        final SkillRegistry skills = new SkillRegistry();
        skills.register(new SkillDefinition(REPORTER, "print a report", "body", List.of()));
        skills.register(new SkillDefinition("quiet", "hidden helper", "body", List.of(),
            null, false));

        final JsonObject payload = PageCatalogEvents.build(
            List.of(new CommandDefinition("review", "review a change", "t", null, false),
                new CommandDefinition("standup", "", "t", null, false)),
            skills);

        Assertions.assertEquals(2, payload.getAsJsonArray(COMMANDS).size());
        final JsonObject first = payload.getAsJsonArray(COMMANDS).get(0).getAsJsonObject();
        Assertions.assertEquals("review", first.get(NAME).getAsString());
        Assertions.assertEquals("review a change", first.get(DESCRIPTION).getAsString());

        Assertions.assertEquals(2, payload.getAsJsonArray(SKILLS).size());
        Assertions.assertEquals(REPORTER,
            payload.getAsJsonArray(SKILLS).get(0).getAsJsonObject().get(NAME).getAsString());

        // profiles arrive with a later milestone: the page must be able to
        // read the field today, as an empty array rather than a gap
        Assertions.assertTrue(payload.getAsJsonArray(PROFILES).isEmpty());
    }

    @Test
    @DisplayName("a workspace with nothing installed still gets a well-formed catalog")
    void emptySectionsAreEmptyArrays() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry());

        Assertions.assertTrue(payload.getAsJsonArray(COMMANDS).isEmpty());
        Assertions.assertTrue(payload.getAsJsonArray(SKILLS).isEmpty());
        Assertions.assertTrue(payload.getAsJsonArray(PROFILES).isEmpty());
    }

    @Test
    @DisplayName("profiles join the catalog as soon as the workspace has them")
    void profilesAreListedAndHiddenOnesStayOut() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(),
            List.of(AgentProfile.builder(REVIEWER).description(REVIEW_ONLY).system(SYSTEM)
                    .maxSteps(4).mode(AgentProfile.Mode.PRIMARY)
                    .tools(Set.of(READ_FILE)).build(),
                AgentProfile.builder(INTERNAL).description(NOT_FOR_CHIP).system(SYSTEM)
                    .tools(Set.of()).hidden(true).build()));

        final JsonArray profiles = payload.getAsJsonArray(PROFILES);
        Assertions.assertEquals(1, profiles.size(),
            "a hidden profile is not something the user picks from a chip");
        final JsonObject profile = profiles.get(0).getAsJsonObject();
        Assertions.assertEquals(REVIEWER, profile.get(NAME).getAsString());
        Assertions.assertEquals(REVIEW_ONLY, profile.get(DESCRIPTION).getAsString());
    }

    @Test
    @DisplayName("the contacts rail leads with the default colleague and keeps chat profiles")
    void contactsLeadWithDefaultAndKeepChatProfiles() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(),
            List.of(AgentProfile.builder(REVIEWER).description(REVIEW_ONLY).system(SYSTEM)
                    .maxSteps(4).mode(AgentProfile.Mode.PRIMARY).tools(Set.of()).build(),
                AgentProfile.builder(HELPER).description(DELEGATE_WORKER).system(SYSTEM)
                    .tools(Set.of()).build(),
                AgentProfile.builder(DELEGATED).description(SUBAGENT_ONLY).system(SYSTEM)
                    .mode(AgentProfile.Mode.SUBAGENT).tools(Set.of()).build(),
                AgentProfile.builder(INVISIBLE).description(NOT_FOR_RAIL).system(SYSTEM)
                    .tools(Set.of()).hidden(true).build()));

        final JsonArray contacts = payload.getAsJsonArray(CONTACTS);
        Assertions.assertEquals(3, contacts.size(),
            "default plus the two profiles allowed to run as the chat itself");
        final JsonObject builtIn = contacts.get(0).getAsJsonObject();
        Assertions.assertEquals(BUILT_IN, builtIn.get(NAME).getAsString(),
            "the built-in colleague is picked by the empty profile name");
        Assertions.assertEquals(DEFAULT_LABEL, builtIn.get(LABEL).getAsString());
        Assertions.assertEquals(DEFAULT_MODE, builtIn.get(MODE).getAsString());
        final JsonObject reviewer = contacts.get(1).getAsJsonObject();
        Assertions.assertEquals(REVIEWER, reviewer.get(NAME).getAsString());
        Assertions.assertEquals(REVIEW_ONLY, reviewer.get(DESCRIPTION).getAsString());
        Assertions.assertEquals(PRIMARY_MODE, reviewer.get(MODE).getAsString());
        Assertions.assertEquals(HELPER,
            contacts.get(2).getAsJsonObject().get(NAME).getAsString());
    }

    @Test
    @DisplayName("with no workspace profiles the rail still offers the default contact")
    void contactsAlwaysCarryTheDefault() {
        final JsonObject payload = PageCatalogEvents.build(List.of(), new SkillRegistry(), null);
        final JsonArray contacts = payload.getAsJsonArray(CONTACTS);
        Assertions.assertEquals(1, contacts.size());
        Assertions.assertEquals(BUILT_IN,
            contacts.get(0).getAsJsonObject().get(NAME).getAsString());
    }

    @Test
    @DisplayName("the agents page cards lead with the built-in and carry the model")
    void agentCardsLeadWithBuiltInAndCarryModel() {
        final JsonArray cards = PageCatalogEvents.agents(
            List.of(AgentProfile.builder(ANALYST).description(ANALYST_DESCRIPTION)
                    .system(SYSTEM).model(ANALYST_MODEL).mode(AgentProfile.Mode.PRIMARY)
                    .tools(Set.of()).build(),
                AgentProfile.builder(DELEGATED).description(SUBAGENT_ONLY).system(SYSTEM)
                    .mode(AgentProfile.Mode.SUBAGENT).tools(Set.of()).build(),
                AgentProfile.builder(INVISIBLE).description(NOT_FOR_PAGE).system(SYSTEM)
                    .tools(Set.of()).hidden(true).build()));

        Assertions.assertEquals(2, cards.size(),
            "built-in plus only the profiles allowed to run as the chat itself");
        final JsonObject builtIn = cards.get(0).getAsJsonObject();
        Assertions.assertEquals(BUILT_IN, builtIn.get(NAME).getAsString());
        Assertions.assertEquals(DEFAULT_LABEL, builtIn.get(LABEL).getAsString());
        Assertions.assertTrue(builtIn.get(BUILTIN).getAsBoolean());
        Assertions.assertEquals(BUILT_IN, builtIn.get(MODEL).getAsString(),
            "the built-in colleague follows whatever provider model is default");
        final JsonObject analyst = cards.get(1).getAsJsonObject();
        Assertions.assertEquals(ANALYST, analyst.get(NAME).getAsString());
        Assertions.assertEquals(ANALYST, analyst.get(LABEL).getAsString());
        Assertions.assertEquals(ANALYST_DESCRIPTION,
            analyst.get(DESCRIPTION).getAsString());
        Assertions.assertEquals(ANALYST_MODEL, analyst.get(MODEL).getAsString());
        Assertions.assertFalse(analyst.has(BUILTIN),
            "only the built-in card marks itself as built-in");
    }

    @Test
    @DisplayName("with no profiles at all the page still offers the built-in card")
    void agentCardsAlwaysCarryTheBuiltIn() {
        Assertions.assertEquals(1, PageCatalogEvents.agents(null).size());
        Assertions.assertEquals(1, PageCatalogEvents.agents(List.of()).size());
    }

    @Test
    @DisplayName("the card shows the profile title as its label, the id stays pickable")
    void agentCardsLabelFollowsTheProfileTitle() {
        final JsonArray cards = PageCatalogEvents.agents(
            List.of(AgentProfile.builder(ANALYST_ID).description(ANALYST_BRIEF)
                .system(SYSTEM_PROMPT).maxSteps(6).mode(AgentProfile.Mode.PRIMARY)
                .tools(Set.of(READ_FILE, SEARCH_CODE)).title(ANALYST_TITLE).build()));

        final JsonObject card = cards.get(1).getAsJsonObject();
        Assertions.assertEquals(ANALYST_ID, card.get(NAME).getAsString(),
            "picking still travels by the file-name id");
        Assertions.assertEquals(ANALYST_TITLE, card.get(LABEL).getAsString());
        Assertions.assertEquals(ANALYST_TITLE, card.get(TITLE).getAsString());
        Assertions.assertEquals(6, card.get(MAX_STEPS).getAsInt());
        Assertions.assertEquals(TOOLS_LIST, card.get(TOOLS).getAsString());
        Assertions.assertEquals(SYSTEM_PROMPT, card.get(SYSTEM).getAsString(),
            "the edit form repopulates from the card, so the prompt travels with it");
    }
}
