package me.bytefox.livingcolonists.llm;

import com.google.gson.JsonObject;

/**
 * A function the model may call.
 *
 * @param parameters JSON Schema of the arguments
 */
public record ToolSpec(String name, String description, JsonObject parameters) {
    public ToolSpec {
        parameters = parameters.deepCopy();
    }

    @Override
    public JsonObject parameters() {
        return parameters.deepCopy();
    }
}
