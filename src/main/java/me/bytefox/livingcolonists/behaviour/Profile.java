package me.bytefox.livingcolonists.behaviour;

import java.util.EnumMap;
import java.util.Map;

/** A preset of module levels, from cheapest to most immersive. */
public enum Profile {
    /** Orders and answers only: colonists know their work, but have no character and no memory. */
    MINIMAL(Level.OFF, Level.LOW, Level.OFF, Level.OFF, Level.OFF),
    LIGHT(Level.LOW, Level.DEFAULT, Level.LOW, Level.LOW, Level.LOW),
    DEFAULT(Level.DEFAULT, Level.DEFAULT, Level.DEFAULT, Level.DEFAULT, Level.DEFAULT),
    /** Colonists remember much more and know their colony in detail. */
    IMMERSIVE(Level.HIGH, Level.HIGH, Level.HIGH, Level.HIGH, Level.HIGH);

    private final Map<Module, Level> levels = new EnumMap<>(Module.class);

    Profile(Level personality, Level perception, Level memory, Level conversation, Level initiative) {
        levels.put(Module.PERSONALITY, personality);
        levels.put(Module.PERCEPTION, perception);
        levels.put(Module.MEMORY, memory);
        levels.put(Module.CONVERSATION, conversation);
        levels.put(Module.INITIATIVE, initiative);
    }

    public Level level(Module module) {
        return levels.get(module);
    }
}
