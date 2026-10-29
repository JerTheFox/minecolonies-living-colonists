package me.bytefox.livingcolonists.behaviour;

import java.util.Locale;

/** The level a module actually runs at: its override from the config, or else the profile's. */
public final class Levels {
    /** Config value meaning "no override, follow the profile". */
    public static final String FROM_PROFILE = "profile";

    private Levels() {
    }

    public static Level resolve(Profile profile, Module module, String override) {
        if (override != null) {
            for (Level level : Level.values()) {
                if (level.name().equalsIgnoreCase(override.strip())) {
                    return level;
                }
            }
        }
        return profile.level(module);
    }

    /** Config values a module override accepts. */
    public static String[] overrideValues() {
        Level[] levels = Level.values();
        String[] values = new String[levels.length + 1];
        values[0] = FROM_PROFILE;
        for (int i = 0; i < levels.length; i++) {
            values[i + 1] = levels[i].name().toLowerCase(Locale.ROOT);
        }
        return values;
    }
}
