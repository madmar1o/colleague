package com.mad.edt.colleague.edit;

/**
 * One requested text change: an exact fragment of the current file content
 * and the text that must take its place. A block with empty search text
 * replaces the whole file.
 */
public final class EditBlock {

    private final String searchText;
    private final String replaceText;

    /**
     * @param theSearchText exact fragment to find, {@code null} for none
     * @param theReplaceText text to put in its place, {@code null} for none
     */
    public EditBlock(final String theSearchText, final String theReplaceText) {
        searchText = theSearchText == null ? "" : theSearchText;
        replaceText = theReplaceText == null ? "" : theReplaceText;
    }

    /**
     * @return the fragment to locate in the file
     */
    public String getSearchText() {
        return searchText;
    }

    /**
     * @return the text that replaces the located fragment
     */
    public String getReplaceText() {
        return replaceText;
    }

    @Override
    public String toString() {
        return "EditBlock[" + searchText.length() + " chars -> " + replaceText.length()
            + " chars]";
    }
}
