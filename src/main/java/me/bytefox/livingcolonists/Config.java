package me.bytefox.livingcolonists;

import java.util.Optional;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings of the language model connection, in {@code config/livingcolonists-common.toml}.
 * The API key itself is never stored here: the config only names the environment variable
 * that holds it.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Connection to an OpenAI-compatible chat completions API").push("llm");
    }

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .comment("Base URL of the API, without /chat/completions")
            .define("baseUrl", "https://api.deepseek.com");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Model id as the provider's /models endpoint lists it")
            .define("model", "deepseek-flash");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV = BUILDER
            .comment("Name of the environment variable that holds the API key")
            .define("apiKeyEnv", "LIVINGCOLONISTS_API_KEY");

    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS = BUILDER
            .comment("How long to wait for a reply before giving up")
            .defineInRange("timeoutSeconds", 30, 1, 300);

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
        BUILDER.pop();
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

    /** The API key from the configured environment variable, if it is set and not blank. */
    public static Optional<String> apiKey() {
        return Optional.ofNullable(System.getenv(API_KEY_ENV.get()))
                .filter(key -> !key.isBlank());
    }
}
