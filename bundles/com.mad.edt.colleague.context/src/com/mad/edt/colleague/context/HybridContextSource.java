package com.mad.edt.colleague.context;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import com.mad.edt.colleague.context.vector.VectorIndex;
import com.mad.edt.colleague.context.vector.VectorIndex.IndexFailure;
import com.mad.edt.colleague.context.vector.VectorIndex.ModuleHit;

/**
 * Hybrid context: the keyword search answer first, then semantic module
 * chunks from the vector index behind it. The semantic part is opportunistic
 * - if the index is not configured, unreachable or fails, the keyword text is
 * returned untouched and the failure is only remembered for diagnostics.
 * Chunks of files the keyword section already quoted are skipped, and the
 * semantic section stops at its character budget.
 */
public final class HybridContextSource implements ContextSource {

    /** Characters the semantic section may add at most. */
    public static final int SEMANTIC_CHAR_BUDGET = 2_400;

    /** Characters of one stored chunk before it is cut. */
    public static final int CHUNK_TEXT_CHARS = 900;

    /** How many strongest chunks the semantic section takes. */
    public static final int SEMANTIC_LIMIT = 4;

    /** Lowest cosine similarity a chunk needs to be quoted. */
    public static final double MIN_SIMILARITY = 0.30;

    private final ContextSource keyword;
    private final Supplier<VectorIndex> semanticIndex;
    private volatile String lastSemanticError = "";

    /**
     * @param theKeyword keyword search supplying the first section
     * @param theSemanticIndex provides the ready vector index, may throw or
     *            return {@code null} while the index is unavailable
     */
    public HybridContextSource(final ContextSource theKeyword,
        final Supplier<VectorIndex> theSemanticIndex) {
        keyword = theKeyword;
        semanticIndex = theSemanticIndex;
    }

    /** {@inheritDoc} */
    @Override
    public String gather(final String theQuestion) {
        final String keywordText = keyword.gather(theQuestion);
        final VectorIndex index;
        try {
            index = semanticIndex.get();
        } catch (final RuntimeException ex) {
            lastSemanticError = describe(ex);
            return keywordText;
        }
        if(index == null) {
            lastSemanticError = "";
            return keywordText;
        }
        final List<ModuleHit> hits;
        try {
            hits = index.searchModules(theQuestion, SEMANTIC_LIMIT,
                Double.valueOf(MIN_SIMILARITY));
        } catch (final IndexFailure | RuntimeException ex) {
            lastSemanticError = describe(ex);
            return keywordText;
        }
        lastSemanticError = "";
        if(hits.isEmpty()) {
            return keywordText;
        }
        final Set<String> quotedPaths = quotedPaths(keywordText);
        final StringBuilder semantic = new StringBuilder();
        for(final ModuleHit hit : hits) {
            if(quotedPaths.contains(hit.path())) {
                continue;
            }
            final String block = block(hit);
            if(semantic.length() + block.length() > SEMANTIC_CHAR_BUDGET) {
                break;
            }
            semantic.append(block);
        }
        if(semantic.length() == 0) {
            return keywordText;
        }
        final StringBuilder result = new StringBuilder(keywordText);
        if(result.length() > 0) {
            result.append("Semantic module chunks:\n");
        }
        result.append(semantic);
        return result.toString();
    }

    /**
     * @return the failure of the most recent semantic lookup, empty when the
     *         lookup worked or was not attempted
     */
    public String getLastSemanticError() {
        return lastSemanticError;
    }

    private static String block(final ModuleHit theHit) {
        String text = theHit.text();
        if(text.length() > CHUNK_TEXT_CHARS) {
            text = text.substring(0, CHUNK_TEXT_CHARS) + "...";
        }
        return "File: " + theHit.path() + " (lines " + theHit.startLine() + '-'
            + theHit.endLine() + ", similarity "
            + String.format(Locale.ROOT, "%.2f", Double.valueOf(theHit.score())) + ")\n" + text
            + "\n\n";
    }

    /**
     * Paths the keyword section already quoted, so the semantic section does
     * not repeat the same file.
     */
    private static Set<String> quotedPaths(final String theKeywordText) {
        final Set<String> paths = new HashSet<>();
        for(final String line : theKeywordText.split("\n")) {
            if(line.startsWith("File: ")) {
                int end = line.indexOf(" (line ");
                if(end < 0) {
                    end = line.length();
                }
                paths.add(line.substring("File: ".length(), end));
            }
        }
        return paths;
    }

    private static String describe(final Throwable theError) {
        final String message = theError.getMessage();
        return message == null ? theError.getClass().getSimpleName() : message;
    }
}
