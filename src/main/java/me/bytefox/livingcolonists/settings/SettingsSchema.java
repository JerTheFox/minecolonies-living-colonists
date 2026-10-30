package me.bytefox.livingcolonists.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Levels;
import me.bytefox.livingcolonists.behaviour.Profile;
import me.bytefox.livingcolonists.settings.Setting.Kind;
import me.bytefox.livingcolonists.settings.Setting.Tab;
import me.bytefox.livingcolonists.settings.Setting.Type;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Every option of {@code livingcolonists-common.toml} as the settings screen shows it. All of them
 * are read where they are used, so a change takes effect at once, without a restart.
 */
public final class SettingsSchema {
    public static final List<Setting> ALL = List.of(
            text("baseUrl", Tab.CONNECTION, Type.URL, Config.BASE_URL),
            text("chatModel", Tab.CONNECTION, Type.TEXT, Config.CHAT_MODEL),
            text("characterModel", Tab.CONNECTION, Type.TEXT, Config.CHARACTER_MODEL),
            text("apiKey", Tab.CONNECTION, Type.SECRET, Config.API_KEY),
            text("apiKeyEnv", Tab.CONNECTION, Type.TEXT, Config.API_KEY_ENV),
            number("timeoutSeconds", Tab.CONNECTION, Config.TIMEOUT_SECONDS, 1, 300),
            number("retries", Tab.CONNECTION, Config.RETRIES, 0, 5),
            bool("useTools", Tab.CONNECTION, Config.USE_TOOLS),
            number("maxTokens", Tab.CONNECTION, Config.MAX_TOKENS, 16, 32768),
            new Setting("temperature", Tab.CONNECTION, new Kind(Type.DOUBLE, 0, 2, List.of()),
                    () -> String.valueOf(Config.TEMPERATURE.get()), value -> Config.TEMPERATURE.set(Double.parseDouble(value))),
            text("extraParams", Tab.CONNECTION, Type.JSON, Config.EXTRA_PARAMS),
            bool("fallbackEnabled", Tab.CONNECTION, Config.FALLBACK_ENABLED),
            text("fallbackBaseUrl", Tab.CONNECTION, Type.URL, Config.FALLBACK_BASE_URL),
            text("fallbackModel", Tab.CONNECTION, Type.TEXT, Config.FALLBACK_MODEL),
            text("fallbackApiKey", Tab.CONNECTION, Type.SECRET, Config.FALLBACK_API_KEY),
            text("fallbackApiKeyEnv", Tab.CONNECTION, Type.TEXT, Config.FALLBACK_API_KEY_ENV),
            new Setting("profile", Tab.BEHAVIOUR, new Kind(Type.CHOICE, 0, 0,
                    Arrays.stream(Profile.values()).map(Enum::name).toList()),
                    () -> Config.PROFILE.get().name(), value -> Config.PROFILE.set(Profile.valueOf(value))),
            level("personality", Config.PERSONALITY),
            level("perception", Config.PERCEPTION),
            level("memory", Config.MEMORY),
            level("conversation", Config.CONVERSATION),
            level("initiative", Config.INITIATIVE),
            number("cooldownSeconds", Tab.BEHAVIOUR, Config.COOLDOWN_SECONDS, 0, 60),
            new Setting("playerNames", Tab.BEHAVIOUR, Kind.of(Type.LIST),
                    () -> String.join("\n", Config.PLAYER_NAMES.get()),
                    value -> Config.PLAYER_NAMES.set(value.isEmpty() ? List.of() : List.of(value.split("\n")))),
            bool("colonyChatAnywhere", Tab.BEHAVIOUR, Config.COLONY_CHAT_ANYWHERE),
            bool("membersOnly", Tab.BEHAVIOUR, Config.MEMBERS_ONLY),
            number("messagesPerPlayerPerHour", Tab.LIMITS, Config.MESSAGES_PER_PLAYER_PER_HOUR, 0, 100000),
            new Setting("tokensPerDay", Tab.LIMITS, new Kind(Type.LONG, 0, Long.MAX_VALUE, List.of()),
                    () -> String.valueOf(Config.TOKENS_PER_DAY.get()), value -> Config.TOKENS_PER_DAY.set(Long.parseLong(value))));

    private SettingsSchema() {
    }

    public static Optional<Setting> find(String id) {
        return ALL.stream().filter(setting -> setting.id().equals(id)).findFirst();
    }

    private static Setting text(String id, Tab tab, Type type, ModConfigSpec.ConfigValue<String> value) {
        return new Setting(id, tab, Kind.of(type), value::get, value::set);
    }

    private static Setting bool(String id, Tab tab, ModConfigSpec.BooleanValue value) {
        return new Setting(id, tab, Kind.of(Type.BOOL), () -> String.valueOf(value.get()),
                text -> value.set(Boolean.parseBoolean(text)));
    }

    private static Setting number(String id, Tab tab, ModConfigSpec.IntValue value, int min, int max) {
        return new Setting(id, tab, new Kind(Type.INT, min, max, List.of()), () -> String.valueOf(value.get()),
                text -> value.set(Integer.parseInt(text)));
    }

    private static Setting level(String id, ModConfigSpec.ConfigValue<String> value) {
        return new Setting(id, Tab.BEHAVIOUR, new Kind(Type.CHOICE, 0, 0, List.of(Levels.overrideValues())),
                value::get, value::set);
    }
}
