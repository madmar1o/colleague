package com.mad.edt.colleague.chat;

/**
 * Immutable measurement of the prompt one finished turn sent to the endpoint,
 * so "the chat forgot" becomes a visible, checkable number instead of a
 * guess. The estimate stays pessimistic (the same character-based upper
 * bound as {@link com.mad.edt.colleague.llm.PromptTokens}) and is honestly
 * marked with a {@code ~} wherever it is shown.
 *
 * @param estimatedPromptTokens estimated tokens of the request the turn sent,
 *        measured after this turn's compaction
 * @param tokenLimit the token count a prompt may fill before compaction
 *        kicks in: a share of the effective window
 * @param providerWindowTokens the context window the provider declares, or
 *        the conservative local default when it declares none
 * @param historyMessages messages replayed with the request, counted after
 *        the finished exchange was remembered
 * @param hasDigest {@code true} when the replayed history carries the digest
 *        of earlier exchanges
 * @param compactedThisTurn {@code true} when this very turn folded older
 *        exchanges into the digest before sending
 * @param runtimeTruncated {@code true} when the runtime stopped the answer
 *        with the {@code length} reason, i.e. it cut the oldest prompt text
 *        away on its own
 */
public record ContextSnapshot(int estimatedPromptTokens, int tokenLimit,
    int providerWindowTokens, int historyMessages, boolean hasDigest,
    boolean compactedThisTurn, boolean runtimeTruncated) {
}
