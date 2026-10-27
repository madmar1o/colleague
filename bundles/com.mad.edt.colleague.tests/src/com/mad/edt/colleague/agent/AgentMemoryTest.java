package com.mad.edt.colleague.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the lesson memory the agent hands to later sessions: lessons persist
 * across instances over a plain text file, one lesson per line; a repeated
 * lesson is not stored twice; the store is capped with the oldest lesson
 * giving way; newlines inside a lesson are flattened so one lesson stays
 * one line; and a failing save never loses what the memory already knows in
 * this session.
 */
class AgentMemoryTest {

    @TempDir
    private Path ourDir;

    private Path file() {
        return ourDir.resolve("colleague-lessons.txt");
    }

    @Test
    void lessonsSurviveANewInstance() throws IOException {
        final AgentMemory first = new AgentMemory(file());
        first.addLesson("the register recorder needs a lock on postings");
        first.addLesson("prefer virtual tables over manual balancing");

        final AgentMemory reopened = new AgentMemory(file());
        assertEquals(2, reopened.lessonCount());
        assertEquals("the register recorder needs a lock on postings",
            reopened.lessons().get(0));
    }

    @Test
    void repeatedLessonIsNotStoredTwice() throws IOException {
        final AgentMemory memory = new AgentMemory(file());
        assertTrue(memory.addLesson("bump numbers once per posting"));
        assertFalse(memory.addLesson("  bump numbers once per posting  "));
        assertEquals(1, memory.lessonCount());
    }

    @Test
    void cappedStoreGivesWayToTheOldestLesson() {
        final AgentMemory memory = new AgentMemory(file());
        for(int index = 0; index < AgentMemory.MAX_LESSONS + 3; index++) {
            memory.addLesson("lesson " + index);
        }
        assertEquals(AgentMemory.MAX_LESSONS, memory.lessonCount());
        // the three oldest gave way, the newest stayed
        assertEquals("lesson 3", memory.lessons().get(0));
        assertEquals("lesson " + (AgentMemory.MAX_LESSONS + 2),
            memory.lessons().get(memory.lessonCount() - 1));
    }

    @Test
    void newlinesInsideALessonAreFlattened() {
        final AgentMemory memory = new AgentMemory(file());
        memory.addLesson("first part\nsecond part");

        assertEquals(1, memory.lessonCount());
        assertTrue(memory.lessons().get(0).contains("first part second part"));
        assertFalse(memory.render().contains("\nfirst part"));
    }

    @Test
    void renderListsLessonsAndSaysWhenThereAreNone() {
        assertEquals("The lesson memory is empty.", new AgentMemory(file()).render());

        final AgentMemory memory = new AgentMemory(file());
        memory.addLesson("check the lock before the exchange plan");
        assertEquals("Lessons learned in earlier work on this project:"
            + "\n- check the lock before the exchange plan", memory.render());
    }

    @Test
    void aFailingSaveKeepsTheLessonForThisSession() throws IOException {
        // the parent of the memory file is a plain file, so no lesson line
        // can ever reach the disk; the in-memory part must still work
        final Path blocked = ourDir.resolve("colleague-blocked-parent.txt");
        Files.write(blocked, new byte[0]);
        final AgentMemory memory = new AgentMemory(blocked.resolve("lessons.txt"));
        assertTrue(memory.addLesson("even offline the board keeps working"));

        assertEquals(1, memory.lessonCount());
        assertTrue(memory.render().contains("even offline the board keeps working"));
    }

    @Test
    void aLessonOverTheCharLimitIsClippedOnTheBoard() {
        final AgentMemory memory = new AgentMemory(file());
        memory.addLesson("y".repeat(AgentMemory.MAX_LESSON_CHARS + 50));

        assertEquals(1, memory.lessonCount());
        final String kept = memory.lessons().get(0);
        assertTrue(kept.endsWith("..."));
        assertEquals(AgentMemory.MAX_LESSON_CHARS + 3, kept.length());
    }
}
