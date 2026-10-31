package me.bytefox.livingcolonists.llm;

import com.google.gson.JsonObject;

/**
 * An immutable snapshot of the connection settings, taken on the server thread and handed to
 * the HTTP client, so a config reload mid-request cannot change it.
 *
 * @param fallback used after the primary endpoint runs out of retries; null if there is none
 * @param retries    how many times a temporary failure is retried, per endpoint
 * @param background work nobody waits for (chatter, first lines, memory): it waits behind players' answers
 */
public record LlmSettings(LlmEndpoint primary, LlmEndpoint fallback, int timeoutSeconds, int maxTokens,
                          double temperature, JsonObject extraParams, int retries, boolean quickAnswer,
                          boolean background) {

    public LlmSettings {
        extraParams = extraParams.deepCopy();
    }

    public LlmSettings(LlmEndpoint primary, LlmEndpoint fallback, int timeoutSeconds, int maxTokens, double temperature,
                       JsonObject extraParams, int retries) {
        this(primary, fallback, timeoutSeconds, maxTokens, temperature, extraParams, retries, false, false);
    }

    /** The same settings with room for at least {@code tokens}, for tasks a reasoning model thinks long about. */
    public LlmSettings withMinTokens(int tokens) {
        return tokens <= maxTokens ? this
                : new LlmSettings(primary, fallback, timeoutSeconds, tokens, temperature, extraParams, retries, quickAnswer,
                background);
    }

    /**
     * The same settings for a task that needs no thinking (picking whom a message is for): where
     * the provider supports it, the model answers without reasoning first, about twice as fast.
     */
    public LlmSettings quick() {
        return new LlmSettings(primary, fallback, timeoutSeconds, maxTokens, temperature, extraParams, retries, true,
                background);
    }

    /** The same settings for background work: it gives way to players' answers and pauses while the provider fails. */
    public LlmSettings inBackground() {
        return new LlmSettings(primary, fallback, timeoutSeconds, maxTokens, temperature, extraParams, retries, quickAnswer,
                true);
    }

    @Override
    public JsonObject extraParams() {
        return extraParams.deepCopy();
    }
}
