package me.bytefox.livingcolonists;

import java.util.Optional;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.llm.LlmEndpoint;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings of the language model connection, in {@code config/livingcolonists-common.toml}.
 * API keys can be written here or come from environment variables; they are never logged.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Connection to an OpenAI-compatible chat completions API").push("llm");
    }

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .comment("Base URL of the API, without /chat/completions")
            .define("baseUrl", "https://api.deepseek.com");

    public static final ModConfigSpec.ConfigValue<String> CHAT_MODEL = BUILDER
            .comment("Model for replies and small talk, as the provider's /models endpoint lists it. Fast and cheap is best.")
            .define("chatModel", "deepseek-flash");

    public static final ModConfigSpec.ConfigValue<String> CHARACTER_MODEL = BUILDER
            .comment("Optional model for writing colonists' backgrounds and summarising their memories. Used rarely,",
                    "so a smarter, slower model is affordable, for example deepseek-v4-pro. Empty: use chatModel.")
            .define("characterModel", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("API key of the provider. It is stored in this file as plain text: don't share the file.",
                    "Leave empty to read the key from the environment variable named in apiKeyEnv instead.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV = BUILDER
            .comment("Environment variable with the API key, used when apiKey is empty")
            .define("apiKeyEnv", "LIVINGCOLONISTS_API_KEY");

    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS = BUILDER
            .comment("How long to wait for a reply before giving up")
            .defineInRange("timeoutSeconds", 30, 1, 300);

    public static final ModConfigSpec.IntValue RETRIES = BUILDER
            .comment("How many times to retry after a temporary failure (timeout, rate limit, server error),",
                    "waiting 1 s, then 3 s. Wrong keys and bad requests are not retried.")
            .defineInRange("retries", 2, 0, 5);

    public static final ModConfigSpec.IntValue MAX_TOKENS = BUILDER
            .comment("Upper limit on tokens per request. Reasoning models count their thinking here too,",
                    "so keep it well above the length of a chat line.")
            .defineInRange("maxTokens", 2000, 16, 32768);

    public static final ModConfigSpec.DoubleValue TEMPERATURE = BUILDER
            .comment("Sampling temperature: higher is more varied, lower is more predictable")
            .defineInRange("temperature", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.ConfigValue<String> EXTRA_PARAMS = BUILDER
            .comment("Extra JSON fields merged into every request, for provider-specific options.",
                    "Example: {\"thinking\":{\"type\":\"disabled\"}} turns off DeepSeek's thinking mode",
                    "for faster but less careful replies.")
            .define("extraParams", "{}", Config::isJsonObject);

    static {
        BUILDER.comment("A second API to use when the main one fails even after retries, for example a local model")
                .push("fallback");
    }

    public static final ModConfigSpec.BooleanValue FALLBACK_ENABLED = BUILDER
            .define("enabled", false);

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_BASE_URL = BUILDER
            .comment("Base URL of the fallback API, without /chat/completions")
            .define("baseUrl", "http://127.0.0.1:11434/v1");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_MODEL = BUILDER
            .comment("Model of the fallback API, used for every kind of request")
            .define("model", "");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_API_KEY = BUILDER
            .comment("API key of the fallback API, plain text. Empty for APIs that need none, such as a local model.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_API_KEY_ENV = BUILDER
            .comment("Environment variable with the fallback API key, used when apiKey is empty")
            .define("apiKeyEnv", "");

    static {
        BUILDER.pop(2);
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static boolean isJsonObject(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            return JsonParser.parseString(text).isJsonObject();
        } catch (JsonParseException e) {
            return false;
        }
    }

    /** The main API key from the config, or else from the environment variable; empty if neither is set. */
    public static Optional<String> apiKey() {
        return key(API_KEY.get(), API_KEY_ENV.get());
    }

    private static Optional<String> key(String configured, String environmentVariable) {
        if (!configured.isBlank()) {
            return Optional.of(configured.strip());
        }
        if (environmentVariable.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(System.getenv(environmentVariable.strip()))
                .map(String::strip)
                .filter(key -> !key.isEmpty());
    }

    /** A snapshot of the current settings for a kind of request, or empty when there is no API key. Server thread. */
    public static Optional<LlmSettings> llmSettings(ModelRole role) {
        String model = role == ModelRole.CHARACTER && !CHARACTER_MODEL.get().isBlank() ? CHARACTER_MODEL.get() : CHAT_MODEL.get();
        LlmEndpoint fallback = FALLBACK_ENABLED.get() && !FALLBACK_MODEL.get().isBlank()
                ? new LlmEndpoint(FALLBACK_BASE_URL.get(), FALLBACK_MODEL.get(),
                        key(FALLBACK_API_KEY.get(), FALLBACK_API_KEY_ENV.get()).orElse(""))
                : null;
        return apiKey().map(key -> new LlmSettings(new LlmEndpoint(BASE_URL.get(), model, key), fallback,
                TIMEOUT_SECONDS.get(), MAX_TOKENS.get(), TEMPERATURE.get(),
                JsonParser.parseString(EXTRA_PARAMS.get()).getAsJsonObject(), RETRIES.get()));
    }
}
