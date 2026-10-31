package me.bytefox.livingcolonists.memory;

/**
 * A colonist's life in the colony told in a few sentences (#51): facts fade in days, so on a long-running
 * server the character model sums them up once a game week into a story kept apart from them, and the
 * story leads what a reply remembers.
 */
final class LifeStories {
    /** Game ticks between two stories of one colonist: a game week. */
    static final long EVERY = 7 * 24000L;
    /** Facts a colonist needs before there is a story to tell. */
    static final int MIN_FACTS = 5;

    private LifeStories() {
    }

    /** Whether the colonist's story is due: none yet and enough facts, or the last one is a game week old. */
    static boolean due(long written, long now, int facts) {
        return written < 0 ? facts >= MIN_FACTS : now - written >= EVERY;
    }

    /** The memories block with the story first, if there is one. */
    static String withStory(String memories, String story) {
        if (story == null || story.isBlank()) {
            return memories;
        }
        String line = "Your life in the colony so far: " + story.strip();
        return memories.isEmpty() ? line : line + "\n" + memories;
    }
}
