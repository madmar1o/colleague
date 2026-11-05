package com.mad.edt.colleague.context.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the walk's per-file decision: which files the workspace scan may
 * hand to the indexes. Blank modules carry nothing to search or embed, so
 * a zero-length file must not enter either index - its stale semantic
 * points are then purged by location, and no fingerprint has to survive
 * a restart for it.
 */
final class SourceIndexTest {

    @Test
    @DisplayName("a zero-length module is not indexable")
    void zeroLengthFileIsSkipped() {
        assertFalse(SourceIndex.isIndexable("objectmodule.bsl", 0L));
    }

    @Test
    @DisplayName("a non-empty module with a known extension is indexable")
    void nonEmptyKnownExtensionIsIndexable() {
        assertTrue(SourceIndex.isIndexable("objectmodule.bsl", 5L));
        assertTrue(SourceIndex.isIndexable("configuration.mdo", 1L));
    }

    @Test
    @DisplayName("an unknown extension is never indexable, whatever its size")
    void unknownExtensionIsNeverIndexable() {
        assertFalse(SourceIndex.isIndexable("picture.png", 0L));
        assertFalse(SourceIndex.isIndexable("picture.png", 100L));
    }

    @Test
    @DisplayName("a name without a dot is never indexable")
    void extensionlessNameIsNeverIndexable() {
        assertFalse(SourceIndex.isIndexable("README", 10L));
    }
}
