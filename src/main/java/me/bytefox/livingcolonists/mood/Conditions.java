package me.bytefox.livingcolonists.mood;

import java.util.ArrayList;
import java.util.List;

/**
 * Lasting troubles that make a colony glum (#65): hunger, nowhere to live, unhappiness. Each counts only when it touches
 * a third of the adults and at least two of them, so one hungry colonist in a big colony doesn't sour the mood.
 */
public final class Conditions {
    /** MineColonies' saturation below which a colonist is hungry. */
    public static final double HUNGRY = 8;
    /** Happiness (of 10) below which a colonist is unhappy. */
    public static final double UNHAPPY = 5;

    /** @param cause what the model is told, in English */
    public record Trouble(String occasion, String cause) {
    }

    private Conditions() {
    }

    public static List<Trouble> troubles(int adults, int hungry, int homeless, int unhappy) {
        List<Trouble> troubles = new ArrayList<>();
        int threshold = Math.max(2, (adults + 2) / 3);
        if (adults > 0 && hungry >= threshold) {
            troubles.add(new Trouble("hunger", "many are going hungry, " + hungry + " of " + adults));
        }
        if (adults > 0 && homeless >= threshold) {
            troubles.add(new Trouble("homeless", "many have no home, " + homeless + " of " + adults));
        }
        if (adults > 0 && unhappy >= threshold) {
            troubles.add(new Trouble("unhappy", "many are unhappy, " + unhappy + " of " + adults));
        }
        return troubles;
    }
}
