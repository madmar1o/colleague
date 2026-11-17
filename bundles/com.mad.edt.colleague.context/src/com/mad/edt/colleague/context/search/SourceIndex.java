package com.mad.edt.colleague.context.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * File index of the workspace sources: an Eclipse resource walk collects the
 * searchable files, the resulting snapshot becomes visible atomically and can
 * be replaced between queries. Entries are immutable so search passes can
 * work on a snapshot without locking.
 */
public final class SourceIndex {

    /** Source file extensions considered searchable. */
    private static final List<String> OUR_EXTENSIONS = List.of("bsl", "mdo", "java");

    /** Hard cap on collected files, protects huge project trees. */
    public static final int MAX_ENTRIES = 60_000;

    /**
     * Decides whether one workspace file may enter the indexes: only names
     * with a known source extension, and never a zero-length file - a blank
     * module has nothing to search and nothing to embed, and its stale
     * semantic points are purged by location once it drops out of the walk.
     *
     * @param theLowerName lower-cased file name including the extension
     * @param theSize file size in bytes, zero for a blank module
     * @return {@code true} when the file belongs in the indexes
     */
    public static boolean isIndexable(final String theLowerName, final long theSize) {
        if(theSize == 0L) {
            return false;
        }
        final int dot = theLowerName.lastIndexOf('.');
        return dot >= 0 && OUR_EXTENSIONS.contains(theLowerName.substring(dot + 1));
    }

    /** Immutable snapshot of one indexed file. */
    public static final class Entry {

        private final Path ourLocation;
        private final String ourDisplayPath;
        private final String ourLowerPath;

        /**
         * @param theLocation absolute filesystem path, {@code null} never
         *            reaches this constructor
         * @param theDisplayPath project-relative path shown to the model
         */
        public Entry(final Path theLocation, final String theDisplayPath) {
            ourLocation = theLocation;
            ourDisplayPath = theDisplayPath;
            ourLowerPath = theDisplayPath.toLowerCase(Locale.ROOT);
        }

        /** @return absolute filesystem path of the file */
        public Path location() {
            return ourLocation;
        }

        /** @return project-relative path as displayed in results */
        public String displayPath() {
            return ourDisplayPath;
        }

        /** @return display path lowered for matching */
        public String lowerPath() {
            return ourLowerPath;
        }
    }

    /**
     * Copy-on-write snapshot of the index: every rebuild swaps the reference
     * for a fresh immutable list ({@code List.copyOf}), the published list is
     * never mutated, so the volatile swap is the whole publication contract.
     */
    @SuppressWarnings("java:S3077")
    private volatile List<Entry> ourEntries = List.of();
    private volatile boolean ourIndexing;

    /**
     * Rebuilds the index from the workspace tree. Call from a background
     * job; the new index becomes visible atomically when the walk completes.
     *
     * @param theMonitor progress monitor, may be {@code null}
     * @return number of indexed files
     */
    public int rebuildFromWorkspace(final IProgressMonitor theMonitor) {
        ourIndexing = true;
        final List<Entry> collected = new ArrayList<>();
        try {
            ResourcesPlugin.getWorkspace().getRoot().accept(
                (final IResource resource) -> visit(resource, theMonitor, collected));
        } catch (final CoreException ex) {
            // keep whatever was collected before the failure
        } finally {
            ourIndexing = false;
        }
        replace(collected);
        return collected.size();
    }

    /**
     * One node of the workspace walk.
     *
     * @param theMonitor progress monitor, may be {@code null}
     * @param theCollected entries gathered so far
     * @return {@code false} to stop the walk, {@code true} to descend
     */
    private static boolean visit(final IResource theResource, final IProgressMonitor theMonitor,
        final List<Entry> theCollected) {
        if(theMonitor != null && theMonitor.isCanceled()) {
            return false;
        }
        if(theCollected.size() >= MAX_ENTRIES) {
            return false;
        }
        if(theResource.getType() != IResource.FILE) {
            return true;
        }
        addIfIndexable((IFile)theResource, theCollected);
        return false;
    }

    /**
     * @param theFile workspace file to consider
     * @param theCollected entries gathered so far, the entry is added here
     */
    private static void addIfIndexable(final IFile theFile, final List<Entry> theCollected) {
        final String name = theFile.getName().toLowerCase(Locale.ROOT);
        final Path location = theFile.getLocation() == null ? null
            : theFile.getLocation().toFile().toPath();
        if(location == null || !Files.isReadable(location)) {
            return;
        }
        final long size;
        try {
            size = Files.size(location);
        } catch (final IOException ex) {
            return;
        }
        if(!isIndexable(name, size)) {
            return;
        }
        theCollected.add(new Entry(location, theFile.getProjectRelativePath().toPortableString()));
    }

    /**
     * Swaps in a new index snapshot atomically.
     *
     * @param theEntries entries of the new index
     */
    public void replace(final List<Entry> theEntries) {
        ourEntries = List.copyOf(theEntries);
    }

    /** @return current index snapshot for one search pass */
    public List<Entry> snapshot() {
        return ourEntries;
    }

    /** @return number of files in the current index */
    public int size() {
        return ourEntries.size();
    }

    /** @return {@code true} while a workspace rebuild is running */
    public boolean isIndexing() {
        return ourIndexing;
    }
}
