package me.bytefox.livingcolonists.personality;

/**
 * A colonist's character, kept with the world.
 *
 * @param bio   empty until the model has written it
 * @param child whether the background was written for a child; when the colonist grows up, an
 *              adult background is written from it
 */
public record Personality(String archetype, String bio, boolean child) {
    public Personality withArchetype(String newArchetype) {
        return new Personality(newArchetype, bio, child);
    }

    public Personality withBio(String newBio, boolean writtenForChild) {
        return new Personality(archetype, newBio, writtenForChild);
    }
}
