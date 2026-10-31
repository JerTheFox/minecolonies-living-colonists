package me.bytefox.livingcolonists;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.behaviour.LogLevel;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Levels;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.behaviour.Profile;
import me.bytefox.livingcolonists.llm.LlmEndpoint;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.talk.PlayerNames;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings in {@code config/livingcolonists-common.toml}: the language model connection and how
 * colonists behave. API keys can be written here or come from environment variables; they are
 * never logged.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Connection to an OpenAI-compatible chat completions API").translation("livingcolonists.configuration.section.llm").push("llm");
    }

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .translation("livingcolonists.configuration.baseUrl")
            .comment("Address of an OpenAI-compatible API, without /chat/completions. Examples: https://api.deepseek.com,",
                    "https://openrouter.ai/api/v1, http://127.0.0.1:11434/v1 for a local Ollama. Default:",
                    "https://api.deepseek.com.")
            .define("baseUrl", "https://api.deepseek.com");

    public static final ModConfigSpec.ConfigValue<String> CHAT_MODEL = BUILDER
            .translation("livingcolonists.configuration.chatModel")
            .comment("Model for colonists' replies, for working out whom a message is for, and for colony chatter: the",
                    "most used one, so fast and cheap is best. The exact name from the provider's model list (see Load",
                    "models). Default: deepseek-flash.")
            .define("chatModel", "deepseek-flash");

    public static final ModConfigSpec.ConfigValue<String> CHARACTER_MODEL = BUILDER
            .translation("livingcolonists.configuration.characterModel")
            .comment("Model for rare, heavier work: writing colonists' backgrounds and writing conversations down into",
                    "memory. Can be smarter and slower, for example deepseek-v4-pro. Empty: the chat model does it.")
            .define("characterModel", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .translation("livingcolonists.configuration.apiKey")
            .comment("The provider's API key. Kept in the config file as plain text, so don't share the file. Leave empty",
                    "to read the key from the environment variable below instead. Never sent to players.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV = BUILDER
            .translation("livingcolonists.configuration.apiKeyEnv")
            .comment("Name of the environment variable that holds the API key; used when the key above is empty. Default:",
                    "LIVINGCOLONISTS_API_KEY.")
            .define("apiKeyEnv", "LIVINGCOLONISTS_API_KEY");

    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS = BUILDER
            .translation("livingcolonists.configuration.timeoutSeconds")
            .comment("How long to wait for one answer before giving up and retrying, in seconds. From 1 to 300, default",
                    "30. Reasoning models may need 30 to 60.")
            .defineInRange("timeoutSeconds", 30, 1, 300);

    public static final ModConfigSpec.IntValue RETRIES = BUILDER
            .translation("livingcolonists.configuration.retries")
            .comment("How many times to retry after a temporary failure: a timeout, a rate limit (429), a server error",
                    "(5xx) or a lost connection. Waits 1, 3, then 8 seconds. A wrong key or a bad request is not retried.",
                    "From 0 to 5, default 3.")
            .defineInRange("retries", 3, 0, 5);

    public static final ModConfigSpec.BooleanValue USE_TOOLS = BUILDER
            .translation("livingcolonists.configuration.useTools")
            .comment("On: colonists look things up and take orders through function calling (stock, buildings, research,",
                    "jobs, crafting, couriers, guards and more); needs perception above off. Turn off if your provider",
                    "doesn't support tools. Default on.")
            .define("useTools", true);

    public static final ModConfigSpec.IntValue MAX_TOKENS = BUILDER
            .translation("livingcolonists.configuration.maxTokens")
            .comment("Upper limit of tokens for one answer. Reasoning models count their thinking here too, so keep it",
                    "well above a chat line; the mod raises it by itself for heavy tasks. From 16 to 32768, default 2000.")
            .defineInRange("maxTokens", 2000, 16, 32768);

    public static final ModConfigSpec.DoubleValue TEMPERATURE = BUILDER
            .translation("livingcolonists.configuration.temperature")
            .comment("How varied the answers are: 0 is predictable, 1 is natural, 2 is wild. From 0 to 2, default 1.")
            .defineInRange("temperature", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.ConfigValue<String> EXTRA_PARAMS = BUILDER
            .translation("livingcolonists.configuration.extraParams")
            .comment("Extra fields added to every request, as a JSON object, for provider-specific options; {} for none.",
                    "Example: {\"thinking\":{\"type\":\"disabled\"}} turns off DeepSeek's thinking for faster but less careful",
                    "answers.")
            .define("extraParams", "{}", Config::isJsonObject);

    static {
        BUILDER.comment("A second API to use when the main one fails even after retries, for example a local model")
                .translation("livingcolonists.configuration.section.fallback").push("fallback");
    }

    public static final ModConfigSpec.BooleanValue FALLBACK_ENABLED = BUILDER
            .translation("livingcolonists.configuration.fallbackEnabled")
            .comment("On: when the main API still fails after all retries, the request goes to a second API, for example a",
                    "local model. Needs the fallback address and model. Default off.")
            .define("enabled", false);

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_BASE_URL = BUILDER
            .translation("livingcolonists.configuration.fallbackBaseUrl")
            .comment("Address of the fallback API, without /chat/completions. Example for Ollama:",
                    "http://127.0.0.1:11434/v1.")
            .define("baseUrl", "http://127.0.0.1:11434/v1");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_MODEL = BUILDER
            .translation("livingcolonists.configuration.fallbackModel")
            .comment("Model of the fallback API, used for every kind of request. Example: qwen2.5:14b. Empty: no fallback.")
            .define("model", "");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_API_KEY = BUILDER
            .translation("livingcolonists.configuration.fallbackApiKey")
            .comment("Key of the fallback API, plain text. Empty for APIs that need none, such as a local model.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> FALLBACK_API_KEY_ENV = BUILDER
            .translation("livingcolonists.configuration.fallbackApiKeyEnv")
            .comment("Environment variable with the fallback key, used when the key above is empty. Empty: none.")
            .define("apiKeyEnv", "");

    static {
        BUILDER.pop(2);
        BUILDER.comment("Optional: an OpenAI-compatible embeddings API, so colonists recall the remembered facts a talk",
                        "touches by meaning, in any language. Without a key, facts are picked by the words in common.")
                .translation("livingcolonists.configuration.section.embeddings").push("embeddings");
    }

    public static final ModConfigSpec.ConfigValue<String> EMBEDDINGS_BASE_URL = BUILDER
            .translation("livingcolonists.configuration.embeddingsBaseUrl")
            .comment("Address of the embeddings API, without /embeddings. Default: OpenRouter.")
            .define("baseUrl", "https://openrouter.ai/api/v1");

    public static final ModConfigSpec.ConfigValue<String> EMBEDDINGS_MODEL = BUILDER
            .translation("livingcolonists.configuration.embeddingsModel")
            .comment("Embedding model, as the provider names it. It must know the players' languages: baai/bge-m3 does,",
                    "and is cheap and fast. Empty: embeddings off.")
            .define("model", "baai/bge-m3");

    public static final ModConfigSpec.ConfigValue<String> EMBEDDINGS_API_KEY = BUILDER
            .translation("livingcolonists.configuration.embeddingsApiKey")
            .comment("Key of the embeddings API, plain text. Empty: the variable below. Without a key, embeddings are off,",
                    "except for an API on this machine (localhost), which needs none.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> EMBEDDINGS_API_KEY_ENV = BUILDER
            .translation("livingcolonists.configuration.embeddingsApiKeyEnv")
            .comment("Environment variable with the embeddings key, used when the key above is empty.")
            .define("apiKeyEnv", "LIVINGCOLONISTS_EMBEDDINGS_API_KEY");

    static {
        BUILDER.pop();
        BUILDER.comment("How deep colonists' personalities, knowledge and memory go, and who may talk to them")
                .translation("livingcolonists.configuration.section.behaviour").push("behaviour");
    }

    public static final ModConfigSpec.EnumValue<Profile> PROFILE = BUILDER
            .translation("livingcolonists.configuration.profile")
            .comment("How deep colonists go, and how many tokens they spend. MINIMAL: the colony run by chat, plain",
                    "answers, look-ups and orders, no character, memory or chatter, cheapest. LIGHT: a short character and memory. DEFAULT: the balance. IMMERSIVE:",
                    "detailed knowledge, long memory, long colony chats, the most tokens. Personality, perception, memory",
                    "and conversation and initiative can override parts of it. Default: DEFAULT.")
            .defineEnum("profile", Profile.DEFAULT);

    public static final ModConfigSpec.ConfigValue<String> PERSONALITY = levelOverride("personality",
            "Colonists' archetypes and backgrounds. profile: as the profile says. off: none. low: the archetype",
            "only. default: the archetype and a short background. high: a longer background.");

    public static final ModConfigSpec.ConfigValue<String> PERCEPTION = levelOverride("perception",
            "What colonists know about themselves and the colony. profile: as the profile says. off: nothing.",
            "low: their work and needs. default: also home, food, health, mood, weather, family, coworkers and",
            "colony look-ups. high: also siblings and more detail.");

    public static final ModConfigSpec.ConfigValue<String> MEMORY = levelOverride("memory",
            "What colonists remember of talks, promises and gossip. profile: as the profile says. off: nothing.",
            "low: 4 lines of a talk and 5 facts, for about 2 days. default: 10 lines and 15 facts, about a week.",
            "high: 40 lines and 60 facts, about a month.");

    public static final ModConfigSpec.ConfigValue<String> CONVERSATION = levelOverride("conversation",
            "How colony chats run on their own. profile: as the profile says. off: one answer to a player. low:",
            "up to 4 lines. default: up to 8. high: up to 16, colonists chat with each other a lot. Also turns",
            "colonists' own colony talk about events and their promise reminders on or off.");

    public static final ModConfigSpec.ConfigValue<String> INITIATIVE = levelOverride("initiative",
            "How often colonists write to a player first, unprompted: about a problem of theirs (more pointed if",
            "it stays unsolved, until they give up asking), something the player told them, news or small talk.",
            "Any colonist, while the player is in the colony and not in a conversation, at uneven times.",
            "profile: as the profile says. off: never. low: about every half hour. default: about every 12",
            "minutes. high: about every 6 minutes.");

    public static final ModConfigSpec.BooleanValue SPEECH_BUBBLES = BUILDER
            .translation("livingcolonists.configuration.speechBubbles")
            .comment("On: colonists' lines also show as speech bubbles over their heads, and dots while they type; the",
                    "chat line stays. Private lines bubble only for their player. Default on.")
            .define("speechBubbles", true);

    public static final ModConfigSpec.BooleanValue PLAYER_BUBBLES = BUILDER
            .translation("livingcolonists.configuration.playerBubbles")
            .comment("On: players get speech bubbles too, for their chat and colony chat lines, and dots while they type.",
                    "Default off.")
            .define("playerBubbles", false);

    public static final ModConfigSpec.IntValue BUBBLE_RANGE = BUILDER
            .translation("livingcolonists.configuration.bubbleRange")
            .comment("How far away, in blocks, speech bubbles are seen. From 8 to 64, default 24.")
            .defineInRange("bubbleRange", 24, 8, 64);

    public static final ModConfigSpec.IntValue COOLDOWN_SECONDS = BUILDER
            .translation("livingcolonists.configuration.cooldownSeconds")
            .comment("Shortest time between two messages of one player to colonists, in seconds; guards against spam. 0",
                    "turns it off. From 0 to 60, default 3.")
            .defineInRange("cooldownSeconds", 3, 0, 60);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> PLAYER_NAMES = BUILDER
            .translation("livingcolonists.configuration.playerNames")
            .comment("What colonists call players instead of their nicknames. One entry per player:",
                    "Nickname=Name,Other name,Rare name; the first name is used most. In this file it is a list, like",
                    "[\"Steve=Steven,Stevie\", \"Alex=Alexandra,Alex\"]; on the settings screen separate entries with ';', like",
                    "Steve=Steven,Stevie; Alex=Alexandra,Alex. Empty: nicknames.")
            .defineListAllowEmpty("playerNames", List.of(), () -> "Nickname=Name", value -> value instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_ADVANCEMENTS = BUILDER
            .translation("livingcolonists.configuration.extraAdvancements")
            .comment("More advancements colonists hear about and bring up as hearsay when a player comes back, besides",
                    "the big ones built in (the dragon, the Wither, diamonds, netherite and more). One entry per advancement:",
                    "id, or id=what colonists say, in English after \"word has it they\". In this file it is a list, like",
                    "[\"create:andesite_alloy=learned to make andesite alloy\", \"mymod:boss\"]; on the settings screen",
                    "separate entries with ';'. Without words, the advancement's title is used. Empty: only the built-in ones.")
            .defineListAllowEmpty("extraAdvancements", List.of(), () -> "modid:advancement", value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue COLONY_CHAT_ANYWHERE = BUILDER
            .translation("livingcolonists.configuration.colonyChatAnywhere")
            .comment("On: colony members (owner, officers, friends) read their colony's chat and write to it from",
                    "anywhere. Off: only players inside the colony's borders take part. Default off.")
            .define("colonyChatAnywhere", false);

    public static final ModConfigSpec.BooleanValue MEMBERS_ONLY = BUILDER
            .translation("livingcolonists.configuration.membersOnly")
            .comment("On: only the colony's owner, officers and friends may talk to its colonists, and only the owner and",
                    "officers may give orders. Off: any player inside the colony may. Default on.")
            .define("membersOnly", true);

    public static final ModConfigSpec.EnumValue<LogLevel> LOG_LEVEL = BUILDER
            .translation("livingcolonists.configuration.logLevel")
            .comment("How much the mod writes to the server log. ALL: colonists' chat lines, what they do (look-ups, orders,",
                    "promises, memory) and technical lines. NO_CHAT: the same without the chat lines. TECHNICAL: start-up,",
                    "settings changes, warnings and errors. OFF: errors only.")
            .defineEnum("logLevel", LogLevel.ALL);

    static {
        BUILDER.pop();
        BUILDER.comment("Optional limits against chat spam and runaway costs on public servers; 0 turns a limit off")
                .translation("livingcolonists.configuration.section.limits").push("limits");
    }

    public static final ModConfigSpec.IntValue MESSAGES_PER_PLAYER_PER_HOUR = BUILDER
            .translation("livingcolonists.configuration.messagesPerPlayerPerHour")
            .comment("How many messages one player may send to colonists in an hour of server time; after that they wait.",
                    "Against spam on public servers. 0: no limit (default).")
            .defineInRange("messagesPerPlayerPerHour", 0, 0, 100000);

    public static final ModConfigSpec.LongValue TOKENS_PER_DAY = BUILDER
            .translation("livingcolonists.configuration.tokensPerDay")
            .comment("How many tokens (prompt and answer together) the whole server may spend in a day; after that",
                    "colonists rest until midnight. Counted since the server started. 0: no limit (default).")
            .defineInRange("tokensPerDay", 0L, 0L, Long.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static ModConfigSpec.ConfigValue<String> levelOverride(String name, String... help) {
        return BUILDER
                .translation("livingcolonists.configuration." + name)
                .comment(help)
                .define(name, Levels.FROM_PROFILE, Config::isLevelOverride);
    }

    /** A null check first: NeoForge validates missing values as null, which List.of().contains() rejects. */
    private static boolean isLevelOverride(Object value) {
        return value instanceof String text && List.of(Levels.overrideValues()).contains(text);
    }

    /** Nickname (lower case) to the names colonists use, most common first. */
    public static Map<String, List<String>> playerNames() {
        return PlayerNames.parse(PLAYER_NAMES.get());
    }

    /**
     * Colony functions (look-ups and orders) are offered unless turned off or perception is off: they
     * read the colony themselves, whatever the prompt shows. So MINIMAL runs the colony by chat.
     */
    public static boolean toolsEnabled() {
        return USE_TOOLS.get() && level(Module.PERCEPTION) != Level.OFF;
    }

    /** The level a module runs at now: its override, or else the profile's. */
    public static Level level(Module module) {
        String override = switch (module) {
            case PERSONALITY -> PERSONALITY.get();
            case PERCEPTION -> PERCEPTION.get();
            case MEMORY -> MEMORY.get();
            case CONVERSATION -> CONVERSATION.get();
            case INITIATIVE -> INITIATIVE.get();
        };
        return Levels.resolve(PROFILE.get(), module, override);
    }

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

    /**
     * The embeddings API, if one is set up: a model, and a key or an address on this machine. The key
     * is never logged or sent to players.
     */
    public static Optional<LlmEndpoint> embeddings() {
        String model = EMBEDDINGS_MODEL.get().strip();
        if (model.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> key = key(EMBEDDINGS_API_KEY.get(), EMBEDDINGS_API_KEY_ENV.get());
        String base = EMBEDDINGS_BASE_URL.get().strip();
        boolean local = base.matches("(?i)https?://(localhost|127\\.0\\.0\\.1)([:/].*)?");
        return key.isPresent() || local ? Optional.of(new LlmEndpoint(base, model, key.orElse(""))) : Optional.empty();
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
