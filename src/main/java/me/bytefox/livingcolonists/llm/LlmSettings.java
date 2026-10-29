package me.bytefox.livingcolonists.llm;

import com.google.gson.JsonObject;

/**
 * An immutable snapshot of the connection settings, taken on the server thread and handed to
 * the HTTP client, so a config reload mid-request cannot change it.
 */
public record LlmSettings(String baseUrl, String model, String apiKey, int timeoutSeconds, int maxTokens,
                          double temperature, JsonObject extraParams) {

    public LlmSettings {
        extraParams = extraParams.deepCopy();
    }

    @Override
    public JsonObject extraParams() {
        return extraParams.deepCopy();
    }

    /** Never prints the API key. */
    @Override
    public String toString() {
        return "LlmSettings[baseUrl=" + baseUrl + ", model=" + model + ", timeoutSeconds=" + timeoutSeconds
                + ", maxTokens=" + maxTokens + ", temperature=" + temperature + ", extraParams=" + extraParams + "]";
    }
}
