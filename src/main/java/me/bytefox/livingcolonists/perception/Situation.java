package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * Turns {@link ColonistFacts} into the {@code {situation}} part of a reply prompt. How much goes in
 * follows the perception level: low - work and supplies only (enough for orders and "how long
 * still?"), default - also needs, the colony and the closest family and coworkers, high - also
 * the wider family.
 *
 * <p>The rules about work, food and supplies carry lessons from Colonist Errands by Lovkar
 * (GPL-3.0-or-later, https://github.com/Lovkar-Squid/colonist-errands: {@code WorkTruth},
 * {@code FoodCheck}, {@code HomeCheck}, {@code SupplyCheck}): models play along with requests a
 * colonist can't fulfil, beg for food they carry, and mistake a modest house for no house.</p>
 */
public final class Situation {
    private static final double HUNGRY = 8;
    private static final double UNHAPPY = 4;
    private static final double CONTENT = 8;
    private static final double HURT = 0.5;
    /** Family members and coworkers listed at most. */
    private static final int MAX_PEOPLE = 6;

    private Situation() {
    }

    public static String render(ColonistFacts facts, Level level) {
        if (level == Level.OFF) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("What you know right now, to answer questions with:");
        work(facts, lines);
        supplies(facts, lines);
        if (level == Level.DEFAULT || level == Level.HIGH) {
            needs(facts, lines);
            colony(facts, lines);
            people(facts, level == Level.HIGH, lines);
        }
        return String.join("\n", lines);
    }

    private static void work(ColonistFacts facts, List<String> lines) {
        if (facts.job() == null) {
            lines.add("- You have no job right now, so you do no work at all.");
        } else {
            lines.add("- You are a " + facts.job() + workplace(facts) + ".");
        }
        for (String note : facts.workNotes()) {
            lines.add("- " + note);
        }
        String alternative = facts.availableWork().isEmpty() ? ""
                : " The colony has workplaces for " + String.join("; ", facts.availableWork())
                + ", so you could offer to take that job instead.";
        String missing = facts.missingWork().isEmpty() ? ""
                : " Nobody can do " + String.join(", ", facts.missingWork()) + " here until the workplace is built.";
        lines.add("- Work here is a job, not an errand: you only do your own job. If asked to do other work, never pretend"
                + " to start it; say you can't." + alternative + missing);
    }

    private static String workplace(ColonistFacts facts) {
        if (facts.workplaceLevel() <= 0) {
            return "";
        }
        String max = facts.workplaceMaxLevel() > 0 ? " of " + facts.workplaceMaxLevel() : "";
        String room = facts.workplaceMaxLevel() > facts.workplaceLevel() ? ", so it could still be upgraded" : "";
        return ", at a level " + facts.workplaceLevel() + max + " workplace" + room;
    }

    private static void supplies(ColonistFacts facts, List<String> lines) {
        if (facts.construction() != null) {
            lines.add("- You are working on: " + facts.construction() + "." + (facts.missingMaterials().isEmpty()
                    ? " You have all the materials for it."
                    : " Still missing: " + String.join(", ", facts.missingMaterials()) + ".")
                    + (facts.materialsOnTheWay() > 0 ? " Some of it is already being delivered." : ""));
        } else if ("builder".equals(facts.job())) {
            lines.add("- You have no construction order right now.");
        }
        if (!facts.playerMustBring().isEmpty()) {
            lines.add("- Nobody in the colony can provide " + String.join(", ", facts.playerMustBring())
                    + ": no courier, warehouse or crafter can, so a player has to bring it. Say so plainly when asked.");
        }
        if (facts.requestsUnderway() > 0) {
            lines.add("- Your workplace waits for " + facts.requestsUnderway() + (facts.requestsUnderway() == 1
                    ? " delivery" : " deliveries") + " that a courier or crafter is already handling;"
                    + " don't ask players to bring them.");
        }
        if (!facts.stuckRequests().isEmpty()) {
            lines.add("- Your workplace still needs " + String.join(", ", facts.stuckRequests())
                    + ", and nobody has taken that on yet. You may mention it if it matters.");
        }
    }

    private static void needs(ColonistFacts facts, List<String> lines) {
        if (facts.homeLevel() <= 0) {
            lines.add("- You have no home and sleep wherever you can.");
        } else {
            lines.add("- You live in a level " + facts.homeLevel() + " house" + (facts.homeLevel() == 1
                    ? ": modest, but it is a home." : "."));
        }
        if (facts.saturation() < HUNGRY) {
            String food = switch (facts.food()) {
                case POCKETS -> " You carry food you can eat at your next break, so don't ask players for food.";
                case RESTAURANT -> " The restaurant has food for you, so don't ask players for food.";
                case NONE -> " There is no food for you in your pockets or at the restaurant.";
                case UNKNOWN -> "";
            };
            lines.add("- You are hungry." + food);
        }
        if (facts.sickness() != null) {
            lines.add("- You are sick with " + facts.sickness() + (facts.beingTreated()
                    ? ", and the hospital is taking care of you." : "."));
        } else if (facts.health() >= 0 && facts.health() < HURT) {
            lines.add("- You are hurt.");
        }
        if (facts.happiness() >= 0 && facts.happiness() < UNHAPPY) {
            lines.add("- You are unhappy" + (facts.unhappyAbout().isEmpty() ? "."
                    : ", mostly about " + String.join(", ", facts.unhappyAbout()) + "."));
        } else if (facts.happiness() >= CONTENT) {
            lines.add("- You are content with life here.");
        }
        lines.add("- The weather: " + facts.weather() + ".");
    }

    private static void colony(ColonistFacts facts, List<String> lines) {
        if (facts.colonySize() > 0) {
            lines.add("- The colony has " + facts.colonySize() + " colonists; " + facts.unemployed() + " without a job, "
                    + facts.homeless() + " without a home.");
        }
        if (facts.raid()) {
            lines.add("- The colony is under a raid right now!");
        }
    }

    private static void people(ColonistFacts facts, boolean wider, List<String> lines) {
        List<ColonistFacts.Relative> people = new ArrayList<>();
        for (ColonistFacts.Relative relative : facts.family()) {
            boolean close = switch (relative.relation()) {
                case "husband", "wife", "son", "daughter", "father", "mother" -> true;
                default -> false;
            };
            if (close || wider) {
                people.add(relative);
            }
        }
        people.addAll(facts.coworkers());
        for (ColonistFacts.Relative person : people.stream().limit(MAX_PEOPLE).toList()) {
            lines.add("- Your " + person.relation() + " " + person.name()
                    + (person.job() == null ? "" : ", " + person.job())
                    + (person.notes().isEmpty() ? "" : " (" + String.join(", ", person.notes()) + ")") + ".");
        }
    }
}
