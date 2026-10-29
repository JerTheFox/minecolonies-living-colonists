package me.bytefox.livingcolonists.llm;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Builds the body of an OpenAI-compatible {@code POST /chat/completions} request. */
public final class ChatRequests {
    private ChatRequests() {
    }

    /**
     * The request body for {@code endpoint}'s model. Extra parameters from the config are merged
     * last, so they can also override the standard fields when a provider needs that.
     */
    public static String toJson(LlmEndpoint endpoint, LlmSettings settings, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        body.add("messages", array);
        body.addProperty("max_tokens", settings.maxTokens());
        body.addProperty("temperature", settings.temperature());
        for (Map.Entry<String, JsonElement> extra : settings.extraParams().entrySet()) {
            body.add(extra.getKey(), extra.getValue());
        }
        return body.toString();
    }
}
