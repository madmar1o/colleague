package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Talks to the native local model runtime ({@code /api/chat}, {@code /api/tags}).
 */
public class OllamaLLMProvider extends AbstractHttpLLMProvider {

    private static final int PING_TIMEOUT_MS = 5_000;

    /**
     * @param theConfig endpoint configuration; base URL must point at the
     *        server root, e.g. {@code http://127.0.0.1:11435}
     */
    public OllamaLLMProvider(final ProviderConfig theConfig) {
        super(theConfig);
    }

    @Override
    public ChatResult chat(final ChatRequest theRequest) throws LLMException {
        final JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(theRequest.getModel()));
        body.addProperty("stream", false);
        if(theRequest.getThink() != null) {
            body.addProperty("think", theRequest.getThink().booleanValue());
        }
        if(theRequest.getTemperature() != null) {
            body.addProperty("temperature", theRequest.getTemperature().doubleValue());
        }
        if(theRequest.getMaxTokens() != null) {
            final JsonObject options = new JsonObject();
            options.addProperty("num_predict", theRequest.getMaxTokens().intValue());
            body.add("options", options);
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
            postJson("/api/chat", body, getConfig().getReadTimeoutMs());

        String content = "";
        final JsonObject message = response.getAsJsonObject("message");
        if(message != null) {
            content = textOrNull(message, "content");
            if(content == null) {
                content = "";
            }
        }
        return new ChatResult(content, textOrNull(response, "model"),
            textOrNull(response, "done_reason"),
            intOrZero(response, "prompt_eval_count"), intOrZero(response, "eval_count"));
    }

    @Override
    public List<String> listModels() throws LLMException {
        final JsonObject response = getJson("/api/tags", getConfig().getReadTimeoutMs());
        final List<String> names = new ArrayList<>();
        final JsonArray models = response.getAsJsonArray("models");
        if(models != null) {
            for(final var entry : models) {
                final String name = textOrNull(entry.getAsJsonObject(), "name");
                if(name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public boolean isAvailable() {
        try {
            getJson("/api/version", PING_TIMEOUT_MS);
            return true;
        } catch (final LLMException ex) {
            return false;
        }
    }
}
