package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * When colonists notice a player coming back or leaving (#34), and what they may say: code decides,
 * rarely, and only with a reason; a plain hello comes with a small chance. Times are in ticks.
 */
final class ArrivalPolicy {
    /** A game day outside the colony makes coming back an arrival. */
    static final long AWAY = 24000;
    /** A trip to another world or a death counts once the player was out this long (two minutes). */
    static final long MIN_AWAY = 2 * 60 * 20;
    /** Back this soon after leaving the server is a reconnect, not a return (five minutes). */
    static final long RECONNECT = 5 * 60 * 20;
    /** Coming into the colony this soon after joining the server counts as coming back (ten minutes). */
    static final long SESSION_WINDOW = 10 * 60 * 20;
    /** The pause after arriving before a colonist speaks, so the client has loaded. */
    static final long DELAY_MIN = 15 * 20;
    static final long DELAY_SPREAD = 10 * 20;
    /** A goodbye waits this long, in case the player only reconnects. */
    static final long FAREWELL_DELAY = 60 * 20;
    /** A player who wrote this recently is talking already: no greeting on top. */
    static final long JUST_WROTE = 20 * 20;
    private static final int MAX_NEWS = 3;

    /**
     * What is known about a player's return.
     *
     * @param session whether they joined the server a moment ago
     * @param away    game ticks since they were last in the colony
     * @param news    colony events since then, in the past tense
     * @param died    whether they died since
     * @param trip    the other world they went to since, null if none
     * @param held    something notable in their hand, new since last time, null if none
     * @param hurt    whether they look hurt
     * @param deeds   big deeds of theirs colonists heard of since, as "killed the Ender Dragon"
     */
    record Visit(boolean session, long away, List<String> news, boolean died, String trip, String held, boolean hurt,
                 List<String> deeds) {
    }

    private ArrivalPolicy() {
    }

    static boolean qualifies(Visit visit) {
        return visit.session() || visit.away() >= AWAY
                || visit.away() >= MIN_AWAY && (visit.died() || visit.trip() != null || !visit.deeds().isEmpty());
    }

    static boolean hasReason(Visit visit) {
        return !visit.news().isEmpty() || !visit.deeds().isEmpty() || visit.died() || visit.trip() != null || visit.held() != null || visit.hurt()
                || visit.away() >= AWAY;
    }

    /** Odds that a colonist speaks up at all, with a reason or without. */
    static double chance(Level level, boolean reason) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> reason ? 0.3 : 0.03;
            case DEFAULT -> reason ? 0.5 : 0.07;
            case HIGH -> reason ? 0.7 : 0.12;
        };
    }

    static double farewellChance(Level level) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> 0.2;
            case DEFAULT -> 0.35;
            case HIGH -> 0.5;
        };
    }

    /** The shortest time between two greetings (or two goodbyes) for one player. */
    static long playerGap(Level level) {
        return switch (level) {
            case LOW -> 3 * 60 * 60 * 20;
            case HIGH -> 45 * 60 * 20;
            default -> 90 * 60 * 20;
        };
    }

    /** The shortest time between two such lines in one colony, whoever came or went. */
    static long colonyGap(Level level) {
        return switch (level) {
            case LOW -> 20 * 60 * 20;
            case HIGH -> 5 * 60 * 20;
            default -> 10 * 60 * 20;
        };
    }

    /** Why the colonist writes: the news first, then at most one more thing. */
    static String purpose(Visit visit, String name) {
        StringBuilder text = new StringBuilder("to greet " + name + ", who has just come back to the colony.");
        if (!hasReason(visit)) {
            return text.append(" Nothing more to it: one short line, in your own way.").toString();
        }
        List<String> news = visit.news().stream().limit(MAX_NEWS).toList();
        if (!news.isEmpty()) {
            text.append(" While they were away: ").append(String.join(" ", news))
                    .append(" Tell them what matters most of it, as news.");
        }
        List<String> more = new ArrayList<>();
        if (visit.died()) {
            more.add(" Word is they died out there since you last saw them; ask how they are, in your own way."
                    + " Don't make up how it happened.");
        }
        if (!visit.deeds().isEmpty()) {
            more.add(" Word has it they " + visit.deeds().get(visit.deeds().size() - 1) + "; you may ask about it, as"
                    + " hearsay. Don't make up any details.");
        }
        if (visit.trip() != null) {
            more.add(" They have just come back from " + visit.trip() + ", and it shows on them; you may remark on it."
                    + " Don't make up what they did there.");
        }
        if (visit.hurt()) {
            more.add(" They look hurt.");
        }
        if (visit.held() != null) {
            more.add(" They carry something you haven't seen on them before: " + visit.held() + "; you may ask about it.");
        }
        if (visit.away() >= AWAY) {
            long days = visit.away() / AWAY;
            more.add(" You haven't seen them for " + days + (days == 1 ? " day." : " days."));
        }
        if (!more.isEmpty()) {
            text.append(more.get(0));
        }
        return text.append(" Keep it short, one or two lines.").toString();
    }

    /** A colony event as it is told later: "The Tavern was finished."; null for what isn't news afterwards. */
    static String past(String what) {
        if (what.startsWith("Raiders are attacking")) {
            return "Raiders attacked the colony.";
        }
        if (what.startsWith("The raid on the colony is over")) {
            return null;
        }
        return what.replace(" just ", " ");
    }

    /** A world other than the overworld as colonists would call it, by its id; null for the overworld. */
    static String world(String id) {
        if (id.equals("minecraft:overworld")) {
            return null;
        }
        if (id.endsWith("the_nether") || id.endsWith(":nether")) {
            return "the Nether";
        }
        if (id.endsWith("the_end") || id.endsWith(":end")) {
            return "the End";
        }
        return "a far-off place";
    }

    /** What a colonist sees of the player they talk with; empty if nothing stands out. */
    static String look(String name, String held, String armour, double health) {
        List<String> parts = new ArrayList<>();
        if (held != null) {
            parts.add("holds " + held);
        }
        if (armour != null) {
            parts.add("wears " + armour);
        }
        if (health < 0.25) {
            parts.add("looks badly hurt");
        } else if (health < 0.5) {
            parts.add("looks hurt");
        }
        return parts.isEmpty() ? "" : "What you can see of " + name + ": " + String.join("; ", parts)
                + ". Bring it up only if it fits or stands out.";
    }
}
