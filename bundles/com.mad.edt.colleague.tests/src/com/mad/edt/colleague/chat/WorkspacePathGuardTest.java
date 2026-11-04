package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
