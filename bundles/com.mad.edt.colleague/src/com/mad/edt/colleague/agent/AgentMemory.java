package com.mad.edt.colleague.agent;

import com.mad.edt.colleague.ColleagueLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lesson memory an agent run leaves behind for the later ones: short
 * notes learned while working on this project - what a quirk really means,
 * which approach paid off - survive restarts in a plain text file, one
 * lesson per line. The store keeps itself useful on its own: a repeated
 * note is not stored twice, one note never spans several lines, an
 * over-full store gives way from its oldest entry, and a failing save only
 * costs persistence, never what this session already knows.
 */
public final class AgentMemory {

    /** How many lessons the store keeps at most. */
    public static final int MAX_LESSONS = 40;

    /** How long one lesson may get before it is clipped. */
    public static final int MAX_LESSON_CHARS = 400;

    private final Path file;
    private final List<String> lessons = new ArrayList<>();

    /**
     * Opens the store over a plain text lessons file; an existing file is
     * read in, a missing one starts an empty memory.
     *
     * @param theFile lessons file, created on the first stored lesson
     */
    public AgentMemory(final Path theFile) {
        file = theFile;
        load();
    }

    /**
     * Stores one lesson for later sessions.
     *
     * @param theLesson lesson text; newlines are flattened, over-long text
     *            is clipped
     * @return {@code true} when the lesson was new and is kept now
     */
    public synchronized boolean addLesson(final String theLesson) {
        final String normalized = normalize(theLesson);
        if(normalized.isEmpty() || lessons.contains(normalized)) {
            return false;
        }
        lessons.add(normalized);
        while(lessons.size() > MAX_LESSONS) {
            lessons.remove(0);
        }
        persist();
        return true;
    }

    /**
     * @return the lessons as a block for the model's instructions, or a
     *         plain statement when there is nothing learned yet
     */
    public synchronized String render() {
        if(lessons.isEmpty()) {
            return "The lesson memory is empty.";
        }
        final StringBuilder rendered = new StringBuilder("Lessons learned in earlier work"
            + " on this project:");
        for(final String lesson : lessons) {
            rendered.append('\n').append("- ").append(lesson);
        }
        return rendered.toString();
    }

    /**
     * @return how many lessons the store keeps
     */
    public synchronized int lessonCount() {
        return lessons.size();
    }

    /**
     * @return snapshot of the kept lessons, oldest first
     */
    public synchronized List<String> lessons() {
        return Collections.unmodifiableList(new ArrayList<>(lessons));
    }

    private void load() {
        try {
            if(Files.exists(file)) {
                for(final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    final String normalized = normalize(line);
                    if(!normalized.isEmpty() && !lessons.contains(normalized)) {
                        lessons.add(normalized);
                    }
                }
                while(lessons.size() > MAX_LESSONS) {
                    lessons.remove(0);
                }
            }
        } catch (final IOException ex) {
            // an unreadable store starts empty: the agent works, it just
            // does not remember, and the next save rewrites the file
            ColleagueLog.error("[colleague] lesson memory unreadable, starting empty: "
                + ex.getMessage());
        }
    }

    private void persist() {
        try {
            Files.writeString(file, String.join("\n", lessons) + "\n", StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            // persistence is the first thing to give up, the in-memory
            // lessons stay: losing the disk must not lose the session
            ColleagueLog.error("[colleague] lesson memory not saved to disk: "
                + ex.getMessage());
        }
    }

    private static String normalize(final String theLesson) {
        if(theLesson == null) {
            return "";
        }
        String flattened = theLesson.strip().replaceAll("\\s*[\r\n]+\\s*", " ");
        if(flattened.length() > MAX_LESSON_CHARS) {
            flattened = flattened.substring(0, MAX_LESSON_CHARS) + "...";
        }
        return flattened;
    }
}
