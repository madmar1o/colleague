package com.mad.edt.colleague.context;

/**
 * Plain keyword search behind the {@link ContextSource} port: one file index
 * pass over the workspace per question, exactly what the assistant used
 * before semantic search existed.
 */
public final class KeywordContextSource implements ContextSource {

    private final CodeSearch ourSearch;

    /**
     * @param theSearch keyword search to delegate to
     */
    public KeywordContextSource(final CodeSearch theSearch) {
        ourSearch = theSearch;
    }

    /** {@inheritDoc} */
    @Override
    public String gather(final String theQuestion) {
        return ourSearch.search(theQuestion);
    }
}
