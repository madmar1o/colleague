package com.mad.edt.colleague.chat;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.commands.CommandDefinition;
import com.mad.edt.colleague.skills.SkillDefinition;
import com.mad.edt.colleague.skills.SkillRegistry;

/**
 * Builds the {@code catalog} bridge event a chat page receives when its tab
 * opens: everything the workspace offers - slash commands, project skills and
 * agent profiles - as plain name-plus-description entries. Every section is
 * published as an array even when empty, so the page renders the fields
 * without any special casing.
 */
public final class PageCatalogEvents {

    /**
     * Builds the {@code catalog} payload.
     *
     * @param theCommands slash commands of the workspace, {@code null} for none
     * @param theSkills project skills of the workspace, {@code null} for none
     * @return event payload with a commands, skills and profiles array, each
     *         entry carrying a name and a description
     */
    public static JsonObject build(final List<CommandDefinition> theCommands,
        final SkillRegistry theSkills) {
        return build(theCommands, theSkills, List.of());
    }

    /**
     * Builds the {@code catalog} payload including the agent profiles the user
     * can pick from the profile chip; profiles hidden from that list stay out.
     *
     * @param theCommands slash commands of the workspace, {@code null} for none
     * @param theSkills project skills of the workspace, {@code null} for none
     * @param theProfiles agent profiles of the workspace, {@code null} for none
     * @return event payload with a commands, skills and profiles array, each
     *         entry carrying a name and a description
     */
    public static JsonObject build(final List<CommandDefinition> theCommands,
        final SkillRegistry theSkills, final List<AgentProfile> theProfiles) {
        final JsonObject payload = new JsonObject();
        final JsonArray commands = new JsonArray();
        if(theCommands != null) {
            for(final CommandDefinition command : theCommands) {
                commands.add(entry(command.getName(), command.getDescription()));
            }
        }
        payload.add("commands", commands);
        final JsonArray skills = new JsonArray();
        if(theSkills != null) {
            for(final SkillDefinition skill : theSkills.getAll()) {
                skills.add(entry(skill.getName(), skill.getDescription()));
            }
        }
        payload.add("skills", skills);
        final JsonArray profiles = new JsonArray();
        if(theProfiles != null) {
            for(final AgentProfile profile : theProfiles) {
                if(!profile.isHidden()) {
                    profiles.add(entry(profile.getName(), profile.getDescription()));
                }
            }
        }
        payload.add("profiles", profiles);
        payload.add("contacts", contacts(theProfiles));
        return payload;
    }

    /**
     * The cards the agents page shows: the built-in default colleague first,
     * then every catalog profile allowed to run as the chat itself (profiles
     * marked sub-agent only, and hidden ones, stay out - they just serve
     * delegates). Each card carries the model it asks for, empty meaning the
     * page follows whatever provider model is default.
     *
     * @param theProfiles merged agent catalog, {@code null} for none
     * @return ordered card entries, each with name, label, description and
     *         model; only the built-in card also carries {@code builtin}
     */
    public static JsonArray agents(final List<AgentProfile> theProfiles) {
        final JsonArray cards = new JsonArray();
        final JsonObject builtIn = contact("", "Колега по умолчанию",
            "обычный ассистент по проекту, без ограничений профиля", "default");
        builtIn.addProperty("model", "");
        builtIn.addProperty("builtin", true);
        cards.add(builtIn);
        if(theProfiles != null) {
            for(final AgentProfile profile : theProfiles) {
                if(profile.isHidden() || profile.getMode() == AgentProfile.Mode.SUBAGENT) {
                    continue;
                }
                final JsonObject card = contact(profile.getName(), profile.getDisplayName(),
                    profile.getDescription(),
                    profile.getMode().name().toLowerCase(java.util.Locale.ROOT));
                card.addProperty("model",
                    profile.getModelName() == null ? "" : profile.getModelName());
                // the edit form repopulates straight from the card, so what
                // the writer needs to rewrite the file travels with it
                card.addProperty("title", profile.getTitle());
                card.addProperty("maxSteps", Integer.valueOf(profile.getMaxSteps()));
                card.addProperty("tools", String.join(", ", profile.getToolNamesInOrder()));
                card.addProperty("system", profile.getSystem());
                cards.add(card);
            }
        }
        return cards;
    }

    /**
     * The contacts the chat lists on its left rail: the built-in default
     * colleague first, then every workspace profile allowed to run as the
     * chat itself (profiles marked sub-agent only, and hidden ones, stay out
     * - they just serve delegates). Picking a contact is the existing
     * {@code pickProfile} call, so the default contact travels under the
     * empty profile name exactly like dropping the chip back to default.
     *
     * @param theProfiles agent profiles of the workspace, {@code null} for none
     * @return ordered contact entries, each with name, label, description and mode
     */
    private static JsonArray contacts(final List<AgentProfile> theProfiles) {
        final JsonArray contacts = new JsonArray();
        contacts.add(contact("", "Колега по умолчанию",
            "обычный ассистент по проекту, без ограничений профиля", "default"));
        if(theProfiles != null) {
            for(final AgentProfile profile : theProfiles) {
                if(profile.isHidden() || profile.getMode() == AgentProfile.Mode.SUBAGENT) {
                    continue;
                }
                contacts.add(contact(profile.getName(), profile.getDisplayName(),
                    profile.getDescription(),
                    profile.getMode().name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        return contacts;
    }

    /**
     * One contact rail entry as the page sees it.
     *
     * @param theName profile name to pick, empty for the built-in colleague
     * @param theLabel display name on the rail
     * @param theDescription one-line summary, may be empty
     * @param theMode where the contact runs, as the page reads it
     * @return payload entry for the page
     */
    private static JsonObject contact(final String theName, final String theLabel,
        final String theDescription, final String theMode) {
        final JsonObject contact = new JsonObject();
        contact.addProperty("name", theName);
        contact.addProperty("label", theLabel);
        contact.addProperty("description", theDescription == null ? "" : theDescription);
        contact.addProperty("mode", theMode);
        return contact;
    }

    /**
     * One catalog line as the page sees it.
     *
     * @param theName command or skill name
     * @param theDescription one-line summary, may be empty
     * @return payload entry for the page
     */
    private static JsonObject entry(final String theName, final String theDescription) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("name", theName);
        entry.addProperty("description", theDescription == null ? "" : theDescription);
        return entry;
    }

    private PageCatalogEvents() {
    }
}
