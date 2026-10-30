package me.bytefox.livingcolonists.personality;

/**
 * A colonist's character, kept with the world.
 *
 * @param bio   empty until the model has written it
 * @param child whether the background was written for a child; when the colonist grows up, an
 *              adult background is written from it
 * @param voice a few lines in the colonist's manner of speaking, one per line, written from the
 *              background; empty until written, and written again with a new background
 */
public record Personality(String archetype, String bio, boolean child, String voice) {
    public Personality(String archetype, String bio, boolean child) {
        this(archetype, bio, child, "");
    }

    public Personality withArchetype(String newArchetype) {
        return new Personality(newArchetype, bio, child, "");
    }

    public Personality withBio(String newBio, boolean writtenForChild) {
        return new Personality(archetype, newBio, writtenForChild, "");
    }

    public Personality withVoice(String newVoice) {
        return new Personality(archetype, bio, child, newVoice);
    }
}
