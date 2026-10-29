package me.bytefox.livingcolonists.personality;

import java.util.Map;

/**
 * A kind of character, loaded from {@code config/livingcolonists/archetypes/<id>.json}.
 *
 * @param prompt        how the colonist behaves, in the second person, for the model
 * @param weight        how common the archetype is, relative to the others
 * @param talkativeness 0 to 1: how often the colonist starts a conversation (used when colonists speak first)
 * @param skills        MineColonies skill name to affinity: positive values make the archetype likelier
 *                      for colonists strong in that skill, negative ones for those weak in it
 */
public record Archetype(String id, String name, String prompt, double weight, double talkativeness,
                        Map<String, Double> skills) {

    /** Used when no archetype files are left. */
    public static final Archetype ORDINARY = new Archetype("ordinary", "Ordinary",
            "You are an ordinary, friendly colonist with your own small worries.", 1, 0.5, Map.of());

    public Archetype {
        skills = Map.copyOf(skills);
    }
}
