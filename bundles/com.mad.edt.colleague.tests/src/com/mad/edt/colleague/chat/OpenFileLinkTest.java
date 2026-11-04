package com.mad.edt.colleague.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the answer-link contract of PP-E4: a {@code path:line} token in a chat
 * answer is found and parsed into a workspace-relative path plus a 1-based
 * line, and the path guard rejects anything that could leave the workspace.
 */
class OpenFileLinkTest {

    @Test
    void findsSingleLinkWithLine() {
        final List<ChatFileLink> links =
            ChatFileLink.findIn("Смотри CommonModule/Ext/Module.bsl:42 - там логика.");
        assertEquals(1, links.size());
        assertEquals("CommonModule/Ext/Module.bsl", links.get(0).getPath());
        assertEquals(42, links.get(0).getLine());
    }

    @Test
    void findsSeveralLinksInOneAnswer() {
        final List<ChatFileLink> links = ChatFileLink.findIn(
            "First Catalogs/Orders/Orders.xml:7 и Second Documents/Pay/Pay.bsl:128");
        assertEquals(2, links.size());
        assertEquals(7, links.get(0).getLine());
        assertEquals("Documents/Pay/Pay.bsl", links.get(1).getPath());
    }

    @Test
    void ignoresTokenWithoutProjectSegment() {
        assertTrue(ChatFileLink.findIn("README.md:5 alone").isEmpty());
    }

    @Test
    void ignoresUrlLikeText() {
        assertTrue(ChatFileLink.findIn("see https://edt.example.com/downloads:8080/x").isEmpty());
    }

    @Test
    void guardAcceptsWorkspaceRelativePathOnly() {
        assertTrue(ChatFileLink.isWorkspaceSafe("Catalogs/Orders/Orders.xml"));
        assertFalse(ChatFileLink.isWorkspaceSafe("../outside.bsl"));
        assertFalse(ChatFileLink.isWorkspaceSafe("/abs/path/x.bsl"));
        assertFalse(ChatFileLink.isWorkspaceSafe("C:/Windows/x.bsl"));
        assertFalse(ChatFileLink.isWorkspaceSafe("Proj/secret/../../x.bsl"));
        assertFalse(ChatFileLink.isWorkspaceSafe("SingleSegment.bsl"));
    }

    @Test
    void lineMustBePositive() {
        assertTrue(ChatFileLink.findIn("Proj/src/a.bsl:0").isEmpty());
        assertEquals(1, ChatFileLink.findIn("Proj/src/a.bsl:1").size());
    }
}
