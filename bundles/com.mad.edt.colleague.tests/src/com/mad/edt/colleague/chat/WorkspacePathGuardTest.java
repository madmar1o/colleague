package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins the path guard behind an answer-link click: the page hands over text
 * taken from the model's answer, so the path must be judged against the same
 * workspace rules the file tools apply before any editor is asked to open it.
 */
class WorkspacePathGuardTest {

    @Test
    void workspaceRelativePathWithProjectSegmentIsAccepted() {
        assertTrue(WorkspacePathGuard.isSafe("Catalogs/Orders/Orders.xml"));
        assertTrue(WorkspacePathGuard.isSafe("CommonModule/Ext/Module.bsl"));
        assertTrue(WorkspacePathGuard.isSafe("Проект/ОбщийМодуль/Модуль.bsl"));
    }

    @Test
    void singleSegmentIsRefusedAsNotWorkspaceRelative() {
        assertFalse(WorkspacePathGuard.isSafe("README.md"));
        assertFalse(WorkspacePathGuard.isSafe(""));
        assertFalse(WorkspacePathGuard.isSafe(null));
    }

    @Test
    void escapingOrAbsolutePathsAreRefused() {
        assertFalse(WorkspacePathGuard.isSafe("../outside.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("Proj/secret/../../x.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("/abs/path/x.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("C:/Windows/x.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("Proj\\Modules\\x.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("Proj//Modules/x.bsl"));
        assertFalse(WorkspacePathGuard.isSafe("./Proj/x.bsl"));
    }

    /**
     * The single source of the rule runs through the Java side: every case of
     * the canonical set (shared with the page-side mirror through
     * tools/test_path_guard.mjs) must be judged exactly as its expected flag
     * says. A mismatch here and a mismatch over there are the same defect
     * wearing two languages.
     */
    @Test
    void canonicalCasesFromJsonAreJudgedLikeTheGuard() throws IOException {
        try (java.io.InputStream resource =
                 WorkspacePathGuardTest.class.getResourceAsStream("path-guard-cases.json")) {
            assertTrue(resource != null, "canonical cases resource is missing");
            final JsonObject document = JsonParser.parseString(
                new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
            final JsonArray cases = document.getAsJsonArray("cases");
            assertTrue(cases != null && cases.size() > 0, "canonical set is empty");
            for(final JsonElement element : cases) {
                final JsonObject entry = element.getAsJsonObject();
                final String path = entry.has("path") && entry.get("path").isJsonNull()
                    ? null : entry.get("path").getAsString();
                final boolean expected = entry.get("safe").getAsBoolean();
                assertEquals(expected, WorkspacePathGuard.isSafe(path),
                    "case: [" + path + "]");
            }
        }
    }
}
