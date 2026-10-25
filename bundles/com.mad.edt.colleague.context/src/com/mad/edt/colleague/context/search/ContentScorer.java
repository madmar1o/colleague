package com.mad.edt.colleague.context.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text matching rules of the search: query tokenizing and the two scoring
 * channels, file path hits and content line hits. Stateless and shared by
 * every search pass.
 */
public final class ContentScorer {

    /** Points one query token adds for a match in the file path. */
    public static final int PATH_TOKEN_POINTS = 5;

    /** Points one query token adds for a match on one content line. */
    public static final int LINE_TOKEN_POINTS = 3;

    /** Token pieces shorter than this never become query tokens. */
    private static final int MIN_TOKEN_LENGTH = 3;

    /**
     * Match of the strongest content line of a file.
     *
     * @param lineIndex zero-based line position, {@code -1} when no line
     *            matched
     * @param score summed token points of that line, {@code 0} when none
     *            matched
     */
    public record LineMatch(int lineIndex, int score) {
    }

    private ContentScorer() {
    }

    /**
     * Splits free query text into lower-case match tokens: runs of letters,
     * digits and the underscore, deduplicated in first-occurrence order.
     *
     * @param theQuery query text, may be {@code null}
     * @return tokens to match, empty when nothing usable was found
     */
    public static List<String> tokenize(final String theQuery) {
        final List<String> tokens = new ArrayList<>();
        if(theQuery == null) {
            return tokens;
        }
        final String lowered = theQuery.toLowerCase(Locale.ROOT);
        for(final String part : lowered.split("[^\\p{L}\\p{N}_]+")) {
            if(part.length() >= MIN_TOKEN_LENGTH && !tokens.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /**
     * Scores a file by its path: every token contained in the path adds its
     * points.
     *
     * @param theLowerPath lowered file path
     * @param theQueryTokens tokens from {@link #tokenize(String)}
     * @return total path score
     */
    public static int pathScore(final String theLowerPath, final List<String> theQueryTokens) {
        int score = 0;
        for(final String token : theQueryTokens) {
            if(theLowerPath.contains(token)) {
                score += PATH_TOKEN_POINTS;
            }
        }
        return score;
    }

    /**
     * Finds the line of a file carrying the most query tokens.
     *
     * @param theLines file split into lines
     * @param theQueryTokens tokens from {@link #tokenize(String)}
     * @return best line position and its score
     */
    public static LineMatch bestLine(final String[] theLines,
        final List<String> theQueryTokens) {
        int bestLine = -1;
        int bestScore = 0;
        for(int lineIndex = 0; lineIndex < theLines.length; lineIndex++) {
            final String line = theLines[lineIndex].toLowerCase(Locale.ROOT);
            int lineScore = 0;
            for(final String token : theQueryTokens) {
                if(line.contains(token)) {
                    lineScore += LINE_TOKEN_POINTS;
                }
            }
            if(lineScore > bestScore) {
                bestScore = lineScore;
                bestLine = lineIndex;
            }
        }
        return new LineMatch(bestLine, bestScore);
    }
}
