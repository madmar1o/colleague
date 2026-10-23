package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Talks to any HTTP endpoint implementing the OpenAI-compatible chat
 * completions contract. The base URL must already contain the API version
 * segment, e.g. {@code https://host/v1}.
 */
public class OpenAiCompatibleLLMProvider extends AbstractHttpLLMProvider {

    private static final int PING_TIMEOUT_MS = 10_000;

    /**
     * @param theConfig endpoint configuration; base URL ends with the API
     *        version segment, e.g. {@code https://host/v1}
     */
    public OpenAiCompatibleLLMProvider(final ProviderConfig theConfig) {
        super(theConfig);
    }

    @Override
    public ChatResult chat(final ChatRequest theRequest) throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(theRequest.getModel()));
        body.addProperty("stream", false);
        if(theRequest.getTemperature() != null) {
            body.addProperty("temperature", theRequest.getTemperature().doubleValue());
        }
        if(theRequest.getMaxTokens() != null) {
            body.addProperty("max_tokens", theRequest.getMaxTokens().intValue());
        }

        final JsonArray messages = new JsonArray();
        for(final ChatMessage message : theRequest.getMessages()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole());
            entry.addProperty("content", message.getContent());
            messages.add(entry);
        }
        body.add("messages", messages);

        final JsonObject response =
            postJson("/chat/completions", body, getConfig().getReadTimeoutMs());

        String content = "";
        String finishReason = null;
        final JsonArray choices = response.getAsJsonArray("choices");
        if(choices != null && !choices.isEmpty()) {
            final JsonObject choice = choices.get(0).getAsJsonObject();
            finishReason = textOrNull(choice, "finish_reason");
            final JsonObject message = choice.getAsJsonObject("message");
            if(message != null) {
                content = textOrNull(message, "content");
                if(content == null) {
                    content = "";
                }
            }
        }

        int promptTokens = 0;
        int completionTokens = 0;
        final JsonObject usage = response.getAsJsonObject("usage");
        if(usage != null) {
            promptTokens = intOrZero(usage, "prompt_tokens");
            completionTokens = intOrZero(usage, "completion_tokens");
        }

        return new ChatResult(content, textOrNull(response, "model"), finishReason,
            promptTokens, completionTokens);
    }

    @Override
    public List<String> listModels() throws LLMException {
        final JsonObject response = getJson("/models", getConfig().getReadTimeoutMs());
        final List<String> names = new ArrayList<>();
        final JsonArray data = response.getAsJsonArray("data");
        if(data != null) {
            for(final var entry : data) {
                final String id = textOrNull(entry.getAsJsonObject(), "id");
                if(id != null) {
                    names.add(id);
                }
            }
        }
        return names;
    }

    @Override
    public boolean isAvailable() {
        try {
            getJson("/models", PING_TIMEOUT_MS);
            return true;
        } catch (final LLMException ex) {
            return false;
        }
    }
}
