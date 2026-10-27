package com.mad.edt.colleague.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the remember tool the model calls to keep one lesson for later
 * sessions: it stores the note through the shared memory, reports how many
 * lessons now survive, answers a missing note with an actionable error
 * instead of throwing, and writes to disk so it never runs side by side
 * with other calls.
 */
class MemoryToolTest {

    @TempDir
    private Path ourDir;

    @Test
    void advertisesTheToolUnderAStableName() {
        final MemoryTool tool = new MemoryTool(new AgentMemory(ourDir.resolve("l.txt")));
        assertEquals("remember", tool.getName());
        assertEquals("remember", tool.getSpec().getName());
        assertFalse(tool.getSpec().getDescription().isEmpty());
        assertFalse(tool.isParallelSafe());
    }

    @Test
    void storesTheNoteAndReportsTheLessonCount() throws IOException {
        final Path file = ourDir.resolve("l.txt");
        final AgentMemory memory = new AgentMemory(file);
        final MemoryTool tool = new MemoryTool(memory);

        final String result = tool.execute("{\"note\":\"recalc totals after posting\"}");

        assertTrue(result.startsWith("Remembered."));
        assertTrue(result.contains("1 lesson"));
        assertEquals(1, memory.lessonCount());
        // and the note really reached the disk: a fresh instance finds it
        assertEquals(1, new AgentMemory(file).lessonCount());
    }

    @Test
    void pluralizesTheLessonReport() {
        final MemoryTool tool = new MemoryTool(new AgentMemory(ourDir.resolve("l.txt")));
        tool.execute("{\"note\":\"one\"}");

        assertTrue(tool.execute("{\"note\":\"two\"}").contains("2 lessons"));
    }

    @Test
    void rejectsAMissingOrBlankNote() {
        final MemoryTool tool = new MemoryTool(new AgentMemory(ourDir.resolve("l.txt")));

        assertEquals("ERROR: pass a non-empty note", tool.execute("{}"));
        assertEquals("ERROR: pass a non-empty note", tool.execute("{\"note\":\"   \"}"));
        assertEquals("ERROR: pass a non-empty note", tool.execute("broken json"));
    }
}
