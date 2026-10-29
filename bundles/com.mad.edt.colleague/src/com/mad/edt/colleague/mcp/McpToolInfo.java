package com.mad.edt.colleague.mcp;

import com.google.gson.JsonObject;

/**
 * One tool as {@code tools/list} reported it. The read-only flag comes from
 * the {@code readOnlyHint} annotation when the server bothers to set it; a
 * tool without that hint is treated as a writer, which is the conservative
 * reading of the protocol.
 *
 * @param name tool name as the server knows it, without any prefix
 * @param description human description shown to the model
 * @param inputSchema JSON-Schema of the arguments object, may be {@code null}
 * @param readOnly whether the server declared the call side-effect free
 */
public record McpToolInfo(String name, String description, JsonObject inputSchema,
                          boolean readOnly) {
}
