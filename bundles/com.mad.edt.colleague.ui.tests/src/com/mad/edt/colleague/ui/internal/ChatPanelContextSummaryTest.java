package com.mad.edt.colleague.ui.internal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.chat.ContextSnapshot;

/**
 * Pins the pure formatting of a {@link ContextSnapshot} into the chat status
 * line (spec 0023): the numbers the user needs to see why the chat forgot -
 * prompt estimate, compaction threshold, provider window, replayed history -
 * plus the digest/compaction/truncation marks. The formatter must stay a
 * static pure function so it is testable without SWT.
 */
class ChatPanelContextSummaryTest {

    @Test
    @DisplayName("no snapshot (agent turns) adds nothing to the status line")
    void absentSnapshotYieldsNoSummary() {
        Assertions.assertEquals("", ChatPanel.contextSummary(null));
    }

    @Test
    @DisplayName("plain turn: estimate, limit, window, history count, no digest")
    void summaryCarriesEstimateLimitWindowAndCounters() {
        Assertions.assertEquals("ctx ~4.1k/22.9k tok, окно 32768, история 18, дайджест нет",
            ChatPanel.contextSummary(new ContextSnapshot(4_100, 22_937, 32_768, 18,
                false, false, false)));
    }

    @Test
    @DisplayName("compact turn marks both the digest and the compaction")
    void compactionAndDigestAreMarked() {
        Assertions.assertEquals("ctx ~5k/5.7k tok, окно 8192, история 40, дайджест да, компакция да",
            ChatPanel.contextSummary(new ContextSnapshot(5_000, 5_734, 8_192, 40,
                true, true, false)));
    }

    @Test
    @DisplayName("runtime truncation is appended without hiding the rest")
    void runtimeTruncationIsAppended() {
        Assertions.assertEquals(
            "ctx ~900/716 tok, окно 1024, история 3, дайджест нет, обрезан рантаймом",
            ChatPanel.contextSummary(new ContextSnapshot(900, 716, 1_024, 3,
                false, false, true)));
    }
}
