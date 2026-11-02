package me.bytefox.livingcolonists.social;

import me.bytefox.livingcolonists.mood.Mood;

/**
 * What two colonists chat about without a model (#68): a topic from the moment (the colony's mood, the weather, the time
 * of day, hunger, work), said as a pair of set lines ({@code pair.<topic>.<n>} and its {@code .reply}); someone at odds
 * with the speaker answers with a jab ({@code pair.jab.<n>}) instead.
 */
public final class SmallTalk {
    private SmallTalk() {
    }

    /** The topic for now; {@code roll} (0-1) picks among the everyday ones. */
    public static String topic(Mood mood, boolean rain, long dayTime, boolean hungry, double roll) {
        switch (mood) {
            case FESTIVE, JOY -> {
                return "festive";
            }
            case GRIEF -> {
                return "grief";
            }
            case WORRY, PANIC -> {
                return "worry";
            }
            default -> {
            }
        }
        if (hungry) {
            return "food";
        }
        if (rain && roll < 0.5) {
            return "rain";
        }
        long time = Math.floorMod(dayTime, 24000L);
        if (time >= 12000 && roll < 0.5) {
            return "evening";
        }
        if (time < 3000 && roll < 0.5) {
            return "morning";
        }
        return roll < 0.5 ? "work" : "life";
    }

    /** Whether the one answering is at odds with the speaker, so they answer with a jab. */
    public static boolean jab(int rapportBetween) {
        return rapportBetween <= -20;
    }
}
