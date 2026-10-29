package me.bytefox.livingcolonists.llm;

import com.google.gson.JsonObject;

/**
 * An immutable snapshot of the connection settings, taken on the server thread and handed to
 * the HTTP client, so a config reload mid-request cannot change it.
 *
 * @param fallback used after the primary endpoint runs out of retries; null if there is none
 * @param retries  how many times a temporary failure is retried, per endpoint
 */
public record LlmSettings(LlmEndpoint primary, LlmEndpoint fallback, int timeoutSeconds, int maxTokens,
                          double temperature, JsonObject extraParams, int retries) {

    public LlmSettings {
        extraParams = extraParams.deepCopy();
    }

    /** The same settings with room for at least {@code tokens}, for tasks a reasoning model thinks long about. */
    public LlmSettings withMinTokens(int tokens) {
        return tokens <= maxTokens ? this
                : new LlmSettings(primary, fallback, timeoutSeconds, tokens, temperature, extraParams, retries);
    }

    @Override
    public JsonObject extraParams() {
        return extraParams.deepCopy();
    }
}
