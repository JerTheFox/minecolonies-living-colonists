package me.bytefox.livingcolonists.settings;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One option of the settings screen, backed by a value of the mod's config.
 *
 * @param id    short unique name; also the translation key {@code livingcolonists.configuration.<id>}
 * @param tab   the screen tab it is shown on
 * @param read  the current value as text
 * @param write sets a value already checked by {@link SettingValues}
 */
public record Setting(String id, Tab tab, Kind kind, Supplier<String> read, Consumer<String> write) {

    public enum Tab {
        CONNECTION, BEHAVIOUR, LIMITS
    }

    /** SECRET is an API key: never sent to clients, only whether it is set. */
    public enum Type {
        BOOL, INT, LONG, DOUBLE, TEXT, URL, JSON, CHOICE, LIST, SECRET
    }

    /** @param min the smallest number allowed, @param max the largest; @param choices for CHOICE */
    public record Kind(Type type, double min, double max, List<String> choices) {
        public static Kind of(Type type) {
            return new Kind(type, 0, 0, List.of());
        }
    }

    public String translationKey() {
        return "livingcolonists.configuration." + id;
    }
}
