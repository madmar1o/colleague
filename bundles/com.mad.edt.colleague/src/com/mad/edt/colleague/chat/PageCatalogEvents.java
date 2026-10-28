package com.mad.edt.colleague.chat;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.commands.CommandDefinition;
import com.mad.edt.colleague.skills.SkillDefinition;
import com.mad.edt.colleague.skills.SkillRegistry;

/**
 * Builds the {@code catalog} bridge event a chat page receives when its tab
 * opens: everything the workspace offers - slash commands, project skills and
 * agent profiles - as plain name-plus-description entries. Agent profiles
 * arrive with a later milestone, so their section is published as an empty
 * array today and the page renders the field without any special casing.
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
        // profiles join the catalog when the profile layer lands; the page
        // already reads this field, an empty array keeps that render simple
        payload.add("profiles", new JsonArray());
        return payload;
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
