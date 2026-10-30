package com.mad.edt.colleague.agents;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Writes one agent profile back out as the markdown the loader reads: a
 * frontmatter header (title, description, model, max-steps, mode, tools)
 * over the body that is the agent's system prompt. The file name is the
 * profile id, so an id that is not a safe file name is refused before it
 * reaches the disk. A save replaces the whole file through a temporary file
 * and an atomic move, so an interrupted write can never leave a half header
 * behind. Values of one-line fields are flattened: a newline arriving from a
 * page form cannot forge extra header lines.
 */
public final class AgentProfileWriter {

    /** Ids that are safe as file names below an agents directory. */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private static final String MARKDOWN_SUFFIX = ".md";

    /**
     * @param theAgentsDir agents directory of one catalog level
     * @param theId profile id to look for
     * @return whether a profile file of that id exists right now
     */
    public static boolean exists(final File theAgentsDir, final String theId) {
        requireSafeId(theId);
        return theAgentsDir != null
            && new File(theAgentsDir, theId + MARKDOWN_SUFFIX).isFile();
    }

    /**
     * Writes (or replaces) the profile file below the given directory.
     *
     * @param theAgentsDir agents directory of one catalog level, created when
     *            it is missing
     * @param theProfile profile to store; its name is the file id
     * @return the file written
     * @throws IOException on file system failures
     * @throws IllegalArgumentException when the id is not a safe file name
     */
    public static File save(final File theAgentsDir, final AgentProfile theProfile)
        throws IOException {
        requireSafeId(theProfile.getName());
        ensureDirectory(theAgentsDir);
        final Path target = theAgentsDir.toPath().resolve(theProfile.getName() + MARKDOWN_SUFFIX);
        return writeAtomically(target, render(theProfile));
    }

    /**
     * Stores profile markdown that is already complete - the seed the plugin
     * ships, or text the user pasted in. The text is kept byte for byte, so
     * what the loader reads back is what the user finds in the file.
     *
     * @param theAgentsDir agents directory of one catalog level, created when
     *            it is missing
     * @param theId profile id, also the file name
     * @param theText full markdown of the profile file
     * @return the file written
     * @throws IOException on file system failures
     * @throws IllegalArgumentException when the id is not a safe file name
     */
    public static File saveText(final File theAgentsDir, final String theId, final String theText)
        throws IOException {
        requireSafeId(theId);
        ensureDirectory(theAgentsDir);
        final Path target = theAgentsDir.toPath().resolve(theId + MARKDOWN_SUFFIX);
        return writeAtomically(target, theText);
    }

    /**
     * Removes one profile file.
     *
     * @param theAgentsDir agents directory of one catalog level
     * @param theId profile id to remove
     * @return {@code true} when a file was removed, {@code false} when there
     *         was nothing of that id to remove
     * @throws IOException on file system failures
     * @throws IllegalArgumentException when the id is not a safe file name
     */
    public static boolean delete(final File theAgentsDir, final String theId)
        throws IOException {
        requireSafeId(theId);
        if(theAgentsDir == null) {
            return false;
        }
        return Files.deleteIfExists(
            theAgentsDir.toPath().resolve(theId + MARKDOWN_SUFFIX));
    }

    /**
     * @param theAgentsDir agents directory to have ready for a write
     * @throws IOException when the directory cannot be created or is not a
     *             directory
     */
    private static void ensureDirectory(final File theAgentsDir) throws IOException {
        if(theAgentsDir == null) {
            throw new IOException("no agents directory given");
        }
        if(!theAgentsDir.isDirectory() && !theAgentsDir.mkdirs()
            && !theAgentsDir.isDirectory()) {
            throw new IOException("cannot create agents directory: " + theAgentsDir);
        }
    }

    /**
     * Puts text into the target through a temporary file and an atomic move,
     * so an interrupted save can never leave a half written profile behind.
     *
     * @param theTarget file to replace
     * @param theText full content to store
     * @return the target file
     * @throws IOException on file system failures
     */
    private static File writeAtomically(final Path theTarget, final String theText)
        throws IOException {
        final Path temp = theTarget.resolveSibling(
            theTarget.getFileName() + ".tmp-" + System.nanoTime());
        Files.writeString(temp, theText, StandardCharsets.UTF_8);
        try {
            Files.move(temp, theTarget, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(temp, theTarget, StandardCopyOption.REPLACE_EXISTING);
        }
        return theTarget.toFile();
    }

    /**
     * Rejects ids that would escape the agents directory or break the loader.
     *
     * @param theId profile id as the page sent it
     */
    private static void requireSafeId(final String theId) {
        if(theId == null || !SAFE_ID.matcher(theId).matches()) {
            throw new IllegalArgumentException(
                "unsafe agent id: only lower-case latin letters, digits, '-' and"
                    + " '_' are allowed, up to 64 characters");
        }
    }

    /**
     * @param theProfile profile to serialize
     * @return the full markdown text of its file
     */
    private static String render(final AgentProfile theProfile) {
        final StringBuilder text = new StringBuilder();
        text.append("---\n");
        appendField(text, "title", theProfile.getTitle());
        appendField(text, "description", theProfile.getDescription());
        appendField(text, "model", theProfile.getModelName());
        if(theProfile.getMaxSteps() > 0) {
            text.append("max-steps: ").append(theProfile.getMaxSteps()).append('\n');
        }
        if(theProfile.getMode() != AgentProfile.Mode.ALL) {
            text.append("mode: ")
                .append(theProfile.getMode().name().toLowerCase(java.util.Locale.ROOT))
                .append('\n');
        }
        if(!theProfile.getToolNames().isEmpty()) {
            text.append("tools: ")
                .append(String.join(", ", theProfile.getToolNamesInOrder())).append('\n');
        }
        if(theProfile.isHidden()) {
            text.append("hidden: true\n");
        }
        text.append("---\n").append(theProfile.getSystem()).append('\n');
        return text.toString();
    }

    /**
     * Writes one one-line header field when it carries a value. Line breaks
     * in the value are flattened to spaces so no field can open a second
     * header line.
     *
     * @param theText target being built
     * @param theKey header field name
     * @param theValue raw value, {@code null} or blank skips the field
     */
    private static void appendField(final StringBuilder theText, final String theKey,
        final String theValue) {
        if(theValue == null) {
            return;
        }
        final String flat = theValue.replace('\r', ' ').replace('\n', ' ').trim();
        if(!flat.isEmpty()) {
            theText.append(theKey).append(": ").append(flat).append('\n');
        }
    }

    private AgentProfileWriter() {
    }
}
