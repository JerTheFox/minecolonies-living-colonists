package me.bytefox.livingcolonists.memory;

/**
 * One remembered line of conversation.
 *
 * @param gameTime when it was said, in world game time
 * @param speaker  the name it was said under: a colonist's name or a player's
 */
public record MemoryLine(long gameTime, String speaker, String text) {
}
