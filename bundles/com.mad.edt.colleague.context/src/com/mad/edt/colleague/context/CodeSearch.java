package com.mad.edt.colleague.context;

import java.nio.file.Path;
import java.util.function.BiConsumer;

import org.eclipse.core.runtime.IProgressMonitor;

import com.mad.edt.colleague.context.search.SearchEngine;
import com.mad.edt.colleague.context.search.SourceIndex;

/**
 * Streaming workspace source search for the assistant: a cached file index
 * built by an Eclipse resource walk (safe for very large project trees),
 * plus per-query content scan with hard time and read budgets so a search
 * can never block a question indefinitely. The index lives in
 * {@link SourceIndex}, the budgeted scan in {@link SearchEngine} and the
 * text shaping in {@link SnippetRenderer} via its engine - each rule pinned
 * by its own tests. This facade keeps the caller-facing API unchanged.
 */
public final class CodeSearch {

    private final SourceIndex ourIndex = new SourceIndex();
    private final SearchEngine ourEngine = new SearchEngine();

    /**
     * Rebuilds the file index from the workspace tree. Call from a background
     * job; the new index becomes visible atomically when the walk completes.
     *
     * @param theMonitor progress monitor, may be {@code null}
     * @return number of indexed files
     */
    public int reindex(final IProgressMonitor theMonitor) {
        return ourIndex.rebuildFromWorkspace(theMonitor);
    }

    /**
     * @return {@code true} while {@link #reindex(IProgressMonitor)} is running
     */
    public boolean isIndexing() {
        return ourIndex.isIndexing();
    }

    /**
     * Number of files in the current index.
     *
     * @return index size
     */
    public int size() {
        return ourIndex.size();
    }

    /**
     * Hands every indexed file to the consumer: absolute location and the
     * project-relative display path. Snapshot-stable, so the vector layer can
     * collect the same files the keyword index holds without seeing the
     * internal entry type.
     *
     * @param theConsumer receives {@code (location, displayPath)} per file
     */
    public void forEachSourceFile(final BiConsumer<Path, String> theConsumer) {
        for(final SourceIndex.Entry entry : ourIndex.snapshot()) {
            theConsumer.accept(entry.location(), entry.displayPath());
        }
    }

    /**
     * Searches file paths and contents for the query tokens.
     *
     * @param theQuery free-form query text
     * @return formatted snippets for the model, empty string when nothing
     *         matched; includes a coverage note when budgets cut the scan
     */
    public String search(final String theQuery) {
        return ourEngine.search(theQuery, ourIndex.snapshot());
    }
}
