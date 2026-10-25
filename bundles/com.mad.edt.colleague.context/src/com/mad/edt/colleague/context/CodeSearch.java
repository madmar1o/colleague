package com.mad.edt.colleague.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Streaming workspace source search for the assistant: a cached file index
 * built by an Eclipse resource walk (safe for very large project trees),
 * plus per-query content scan with hard time and read budgets so a search
 * can never block a question indefinitely. Matches are scored by file path
 * hit and by best matching line; the strongest lines are returned as short
 * snippets with paths and line numbers.
 */
public final class CodeSearch {

    /** Source file extensions considered searchable. */
    private static final List<String> SOURCE_EXTENSIONS = List.of("bsl", "mdo", "java");

    private static final int MAX_INDEX_ENTRIES = 60_000;
    private static final long SCAN_BUDGET_MS = 8_000L;
    private static final long READ_BUDGET_BYTES = 120L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_RESULTS = 6;
    private static final int RESULT_CHAR_BUDGET = 3_500;

    /** Immutable snapshot of one indexed file. */
    private static final class Entry {

        private final Path ourLocation;
        private final String ourDisplayPath;
        private final String ourLowerPath;

        Entry(final Path theLocation, final String theDisplayPath) {
            ourLocation = theLocation;
            ourDisplayPath = theDisplayPath;
            ourLowerPath = theDisplayPath.toLowerCase(Locale.ROOT);
        }
    }

    /** One scored search hit. */
    private static final class Hit {

        private final Entry ourEntry;
        private final int ourScore;
        private final int ourLine;
        private final String ourExcerpt;

        Hit(final Entry theEntry, final int theScore, final int theLine, final String theExcerpt) {
            ourEntry = theEntry;
            ourScore = theScore;
            ourLine = theLine;
            ourExcerpt = theExcerpt;
        }
    }

    private volatile List<Entry> ourIndex = List.of();
    private volatile boolean ourIndexing;

    /**
     * Rebuilds the file index from the workspace tree. Call from a background
     * job; the new index becomes visible atomically when the walk completes.
     *
     * @param theMonitor progress monitor, may be {@code null}
     * @return number of indexed files
     */
    public int reindex(final IProgressMonitor theMonitor) {
        ourIndexing = true;
        final List<Entry> collected = new ArrayList<>();
        try {
            ResourcesPlugin.getWorkspace().getRoot().accept((final IResource resource) -> {
                if(theMonitor != null && theMonitor.isCanceled()) {
                    return false;
                }
                if(collected.size() >= MAX_INDEX_ENTRIES) {
                    return false;
                }
                if(resource.getType() != IResource.FILE) {
                    return true;
                }
                final IFile file = (IFile)resource;
                final String name = file.getName().toLowerCase(Locale.ROOT);
                final int dot = name.lastIndexOf('.');
                if(dot < 0 || !SOURCE_EXTENSIONS.contains(name.substring(dot + 1))) {
                    return false;
                }
                final Path location = file.getLocation() == null ? null
                    : file.getLocation().toFile().toPath();
                if(location == null || !Files.isReadable(location)) {
                    return false;
                }
                collected.add(new Entry(location, file.getProjectRelativePath().toPortableString()));
                return false;
            });
        } catch (final CoreException ex) {
            // keep whatever was collected before the failure
        } finally {
            ourIndexing = false;
        }
        ourIndex = List.copyOf(collected);
        return collected.size();
    }

    /**
     * @return {@code true} while {@link #reindex(IProgressMonitor)} is running
     */
    public boolean isIndexing() {
        return ourIndexing;
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
     * Searches file paths and contents for the query tokens.
     *
     * @param theQuery free-form query text
     * @return formatted snippets for the model, empty string when nothing
     *         matched; includes a coverage note when budgets cut the scan
     */
    public String search(final String theQuery) {
        final List<String> tokens = tokenize(theQuery);
        if(tokens.isEmpty() || ourIndex.isEmpty()) {
            return "";
        }
        final List<Entry> snapshot = ourIndex;

        // Pass 1: score every path cheaply, before any file content is read,
        // so path matches survive even when the read budget cuts the scan.
        final int[] nameScores = new int[snapshot.size()];
        final Integer[] order = new Integer[snapshot.size()];
        for(int index = 0; index < snapshot.size(); index++) {
            int nameScore = 0;
            for(final String token : tokens) {
                if(snapshot.get(index).ourLowerPath.contains(token)) {
                    nameScore += 5;
                }
            }
            nameScores[index] = nameScore;
            order[index] = Integer.valueOf(index);
        }
        java.util.Arrays.sort(order, (left, right) ->
            Integer.compare(nameScores[right.intValue()], nameScores[left.intValue()]));

        final long deadline = System.nanoTime() + SCAN_BUDGET_MS * 1_000_000L;
        long readBytes = 0L;
        int scannedFiles = 0;
        int visitedFiles = 0;
        boolean cutByTime = false;
        boolean cutByBytes = false;

        final Comparator<Hit> byScore = Comparator.comparingInt(hit -> hit.ourScore);
        final PriorityQueue<Hit> top = new PriorityQueue<>(byScore);

        for(final Integer position : order) {
            visitedFiles++;
            final int index = position.intValue();
            final Entry entry = snapshot.get(index);
            final int nameScore = nameScores[index];
            if(System.nanoTime() > deadline) {
                cutByTime = true;
                break;
            }
            if(readBytes >= READ_BUDGET_BYTES) {
                cutByBytes = true;
                break;
            }
            final Path location = entry.ourLocation;
            try {
                final long size = Files.size(location);
                if(size > MAX_FILE_BYTES) {
                    continue;
                }
                readBytes += size;
                final String content = new String(Files.readAllBytes(location),
                    StandardCharsets.UTF_8);
                final String[] lines = content.split("\r?\n", -1);
                int bestLine = -1;
                int bestLineScore = 0;
                for(int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                    final String line = lines[lineIndex].toLowerCase(Locale.ROOT);
                    int lineScore = 0;
                    for(final String token : tokens) {
                        if(line.contains(token)) {
                            lineScore += 3;
                        }
                    }
                    if(lineScore > bestLineScore) {
                        bestLineScore = lineScore;
                        bestLine = lineIndex;
                    }
                }
                if(bestLineScore == 0 && nameScore == 0) {
                    continue;
                }
                scannedFiles++;
                final int score = nameScore + bestLineScore;
                final String excerpt = bestLine < 0 ? "" : excerpt(lines, bestLine);
                top.offer(new Hit(entry, score, bestLine + 1, excerpt));
                if(top.size() > MAX_RESULTS) {
                    top.poll();
                }
            } catch (final IOException ex) {
                // unreadable file, skip
            }
        }

        if(top.isEmpty()) {
            return "";
        }
        final List<Hit> hits = new ArrayList<>(top);
        hits.sort(byScore.reversed());
        final StringBuilder result = new StringBuilder();
        for(final Hit hit : hits) {
            final String block = "File: " + hit.ourEntry.ourDisplayPath
                + (hit.ourLine > 0 ? " (line " + hit.ourLine + ")" : "")
                + "\n" + hit.ourExcerpt + "\n\n";
            if(result.length() + block.length() > RESULT_CHAR_BUDGET) {
                break;
            }
            result.append(block);
        }
        final StringBuilder coverage = new StringBuilder();
        if(cutByTime) {
            coverage.append(" (scan cut by 8s time budget)");
        }
        if(cutByBytes) {
            coverage.append(" (scan cut by 120MB read budget)");
        }
        if(!coverage.isEmpty()) {
            result.append("Scanned ").append(scannedFiles).append(" of ")
                .append(visitedFiles).append(" indexed files").append(coverage)
                .append(". Refine the query or the file filter.\n");
        }
        return result.toString();
    }

    private static List<String> tokenize(final String theQuery) {
        final List<String> tokens = new ArrayList<>();
        if(theQuery == null) {
            return tokens;
        }
        final String lowered = theQuery.toLowerCase(Locale.ROOT);
        for(final String part : lowered.split("[^\\p{L}\\p{N}_]+")) {
            if(part.length() >= 3 && !tokens.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static String excerpt(final String[] theLines, final int theLineIndex) {
        final int from = Math.max(0, theLineIndex - 2);
        final int to = Math.min(theLines.length - 1, theLineIndex + 2);
        final StringBuilder excerpt = new StringBuilder();
        for(int index = from; index <= to; index++) {
            final String line = theLines[index].strip();
            if(line.length() > 160) {
                excerpt.append(line, 0, Math.min(
                    theLineIndex == index ? 300 : 160, line.length())).append('\n');
            } else {
                excerpt.append(line).append('\n');
            }
        }
        return excerpt.toString().strip();
    }
}
