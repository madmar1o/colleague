package com.mad.edt.colleague.agents;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mad.edt.colleague.ext.Frontmatter;

/**
 * Loads agent profiles from &lt;workspace&gt;/.colleague/agents/*.md: a
 * frontmatter header (title, description, model, max-steps, mode, tools,
 * hidden) over a body that becomes the system prompt of that agent. The
 * header is read by the shared {@link Frontmatter} parser, the same one
 * skills and commands use. The file name stays the profile id; the
 * {@code title} header is only its display name.
 */
public final class AgentProfileLoader {

    private static final String MARKDOWN_SUFFIX = ".md";

    /**
     * Loads every profile file below the given directory.
     *
     * @param theAgentsDir root directory, missing directory yields empty list
     * @return loaded profiles sorted by name
     * @throws IOException on file system failures
     */
    public List<AgentProfile> loadAll(final File theAgentsDir) throws IOException {
        final List<AgentProfile> profiles = new ArrayList<>();
        if(theAgentsDir == null || !theAgentsDir.isDirectory()) {
            return profiles;
        }
        final File[] files = theAgentsDir.listFiles(
            file -> file.isFile() && file.getName().endsWith(MARKDOWN_SUFFIX));
        if(files == null) {
            return profiles;
        }
        for(final File file : files) {
            final String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            final Frontmatter header = Frontmatter.split(raw);
            final String fileName = file.getName();
            profiles.add(new AgentProfile(
                fileName.substring(0, fileName.length() - MARKDOWN_SUFFIX.length()),
                header.fields().getOrDefault("description", ""),
                header.body(),
                emptyToNull(header.fields().get("model")),
                intOrZero(header.fields().get("max-steps")),
                modeOf(header.fields().get("mode")),
                toolNamesOf(header.fields().get("tools")),
                "true".equalsIgnoreCase(header.fields().getOrDefault("hidden", "false")),
                header.fields().getOrDefault("title", "")));
        }
        profiles.sort((left, right) -> left.getName().compareTo(right.getName()));
        return profiles;
    }

    /**
     * Reads the tool list of a header: comma separated names, blanks dropped.
     *
     * @param theValue raw header value, may be {@code null}
     * @return names in the order written, empty when the header listed none
     */
    private static Set<String> toolNamesOf(final String theValue) {
        final Set<String> names = new LinkedHashSet<>();
        if(theValue == null) {
            return names;
        }
        for(final String part : theValue.split(",")) {
            final String name = part.trim();
            if(!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Maps the mode field; an unknown or missing value leaves the profile
     * usable in both roles.
     *
     * @param theValue raw header value, may be {@code null}
     * @return mode to run this profile in
     */
    private static AgentProfile.Mode modeOf(final String theValue) {
        if(theValue == null) {
            return AgentProfile.Mode.ALL;
        }
        switch(theValue.trim().toLowerCase()) {
            case "primary":
                return AgentProfile.Mode.PRIMARY;
            case "subagent":
                return AgentProfile.Mode.SUBAGENT;
            default:
                return AgentProfile.Mode.ALL;
        }
    }

    /**
     * @param theValue raw header value, may be {@code null}
     * @return parsed number, {@code 0} when absent or not a number
     */
    private static int intOrZero(final String theValue) {
        try {
            return theValue == null ? 0 : Integer.parseInt(theValue.trim());
        } catch (final NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * @param theValue raw header value, may be {@code null}
     * @return the value or {@code null} when it is blank
     */
    private static String emptyToNull(final String theValue) {
        return theValue == null || theValue.isEmpty() ? null : theValue;
    }
}
