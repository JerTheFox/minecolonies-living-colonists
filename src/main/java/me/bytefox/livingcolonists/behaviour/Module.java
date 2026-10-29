package me.bytefox.livingcolonists.behaviour;

/** Parts of the mod whose depth (and token cost) can be set separately. */
public enum Module {
    /** Archetype and background of each colonist. */
    PERSONALITY,
    /** What colonists know about themselves and the colony: needs, work, storage, events. */
    PERCEPTION,
    /** What colonists remember of conversations and promises. */
    MEMORY,
    /** How long colony chat threads run: off answers the player once, high lets colonists talk on and on. */
    CONVERSATION
}
