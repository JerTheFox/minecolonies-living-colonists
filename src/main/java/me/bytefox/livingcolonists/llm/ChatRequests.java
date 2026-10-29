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
        return toJson(endpoint, settings, messages, List.of());
    }

    /** The same, offering the model {@code tools} to call. */
    public static String toJson(LlmEndpoint endpoint, LlmSettings settings, List<ChatMessage> messages,
                                List<ToolSpec> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            if (!message.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : message.toolCalls()) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.arguments());
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", call.id());
                    entry.addProperty("type", "function");
                    entry.add("function", function);
                    calls.add(entry);
                }
                item.add("tool_calls", calls);
            }
            if (message.toolCallId() != null) {
                item.addProperty("tool_call_id", message.toolCallId());
            }
            array.add(item);
        }
        body.add("messages", array);
        if (!tools.isEmpty()) {
            JsonArray specs = new JsonArray();
            for (ToolSpec tool : tools) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", tool.parameters());
                JsonObject spec = new JsonObject();
                spec.addProperty("type", "function");
                spec.add("function", function);
                specs.add(spec);
            }
            body.add("tools", specs);
        }
        body.addProperty("max_tokens", settings.maxTokens());
        body.addProperty("temperature", settings.temperature());
        for (Map.Entry<String, JsonElement> extra : settings.extraParams().entrySet()) {
            body.add(extra.getKey(), extra.getValue());
        }
        return body.toString();
    }
}
