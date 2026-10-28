package com.mad.edt.colleague.ext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a --- delimited key:value header out of a markdown file and
 * returns the header fields plus the untouched body. Shared by skills,
 * commands and agent profiles so each format stays a thin reader over one
 * parser. Only the first colon of a header line separates key from value,
 * so a value may carry colons of its own, and the raw text may arrive with
 * a byte-order mark or CRLF endings from any editor.
 */
public final class Frontmatter {

    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final String FENCE = "---";

    private final Map<String, String> fields;
    private final String body;

    private Frontmatter(final Map<String, String> theFields, final String theBody) {
        fields = Collections.unmodifiableMap(theFields);
        body = theBody;
    }

    /**
     * Splits the raw file text into header fields and body. A file without a
     * leading fence keeps its whole text as the body and no fields.
     *
     * @param theRaw full file text, CRLF and BOM tolerated
     * @return parsed header fields and the body after the closing fence
     */
    public static Frontmatter split(final String theRaw) {
        final Map<String, String> parsed = new LinkedHashMap<>();
        String text = theRaw == null ? "" : theRaw;
        if(!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n");
        String rest = text;
        if(text.startsWith(FENCE + "\n")) {
            final int closing = text.indexOf("\n" + FENCE, FENCE.length() + 1);
            if(closing > 0) {
                final String header = text.substring(FENCE.length() + 1, closing);
                final int bodyStart = text.indexOf('\n', closing + FENCE.length() + 1);
                rest = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
                for(final String line : header.split("\n")) {
                    final int colon = line.indexOf(':');
                    if(colon <= 0) {
                        continue;
                    }
                    parsed.put(line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim());
                }
            }
        }
        return new Frontmatter(parsed, rest.strip());
    }

    /** @return unmodifiable map of header keys to trimmed values */
    public Map<String, String> fields() {
        return fields;
    }

    /** @return the body below the header, stripped */
    public String body() {
        return body;
    }
}
