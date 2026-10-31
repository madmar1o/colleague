package com.mad.edt.colleague.llm;

/**
 * Rough upper bound on the token cost of a prompt, shared by the request
 * builder that sizes the local runtime window and the conversation that
 * decides when to compact its history. Mixed plain and source text
 * tokenizes at well under one token per character, and the flat margin
 * covers the chat template wrapped around the messages, so the estimate is
 * deliberately pessimistic: an overestimate only makes the window a little
 * roomier or the history a little shorter, while an underestimate would let
 * the runtime cut the prompt in silence.
 */
public final class PromptTokens {

    /**
     * Flat allowance for the chat template, role markers and tool-call
     * wrappers that do not belong to any message text.
     */
    private static final int TEMPLATE_MARGIN = 256;

    /**
     * Characters-to-tokens ratio: three tokens per five characters keeps a
     * plain-text prompt under the real tokenizer count on every model seen
     * so far.
     */
    private static final int CHARS_PER_FIVE_TOKENS = 3;

    private PromptTokens() {
        // utility
    }

    /**
     * Estimates the token cost of a prompt from its total text length.
     *
     * @param theCharacters total characters of all prompt texts, negative
     *        values are treated as no text
     * @return estimated token count, never below the template margin
     */
    public static int estimateForCharacters(final int theCharacters) {
        if(theCharacters <= 0) {
            return TEMPLATE_MARGIN;
        }
        return theCharacters / 5 * CHARS_PER_FIVE_TOKENS
            + theCharacters % 5 * CHARS_PER_FIVE_TOKENS / 5 + TEMPLATE_MARGIN;
    }
}
