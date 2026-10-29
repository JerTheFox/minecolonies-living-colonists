package me.bytefox.livingcolonists.talk;

/** Who a player's ordinary chat messages go to when no colonist is picked for a private talk. */
public enum ChatMode {
    /** Normal server chat; colonists don't listen. */
    PLAYERS,
    /** The whole colony hears the message while the player is inside its borders. */
    COLONY;

    public ChatMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
