/*
 * Rules and prompt texts based on PromiseStore, PromiseWatcher and RelationStore from Colonist
 * Errands by Lovkar, licensed GPL-3.0-or-later (https://github.com/Lovkar-Squid/colonist-errands,
 * tag v3.0.0-beta.3, src/me/lovkar/errands/). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Promises and how players treat colonists, kept free of game classes for unit tests: how rapport
 * moves, when a promise lapses, and what the colonist is told about it.
 */
public final class Bonds {
    /** Rapport changes, as in Colonist Errands: rudeness and broken promises weigh a bit more. */
    public static final int KIND = 7;
    public static final int RUDE = -9;
    public static final int KEPT = 8;
    public static final int BROKEN = -10;

    private static final long DAY = 24000;
    /** A promise with a deadline counts as broken this long after it (three days). */
    public static final long GRACE_TICKS = 3 * DAY;
    /** A promise without a deadline is quietly forgotten after this long (ten days). */
    public static final long NO_DEADLINE_TICKS = 10 * DAY;
    /** Kept and broken promises are brought up for this long (two days). */
    private static final long RECENT_TICKS = 2 * DAY;
    /** Words of a building name that don't tell buildings apart. */
    private static final Set<String> COMMON_WORDS = Set.of("the", "of", "and");

    private Bonds() {
    }

    /** Whether an open promise lapses now: broken past its deadline, forgotten without one. */
    public static Optional<Promise.Status> expiry(Promise promise, long now) {
        if (!promise.isOpen()) {
            return Optional.empty();
        }
        if (promise.due() > 0) {
            return now > promise.due() + GRACE_TICKS ? Optional.of(Promise.Status.BROKEN) : Optional.empty();
        }
        return now - promise.made() > NO_DEADLINE_TICKS ? Optional.of(Promise.Status.CANCELLED) : Optional.empty();
    }

    /**
     * Whether a promise speaks of this building: every word of its English name (a plural counts)
     * appears in the promise, so "a tower" doesn't match "Guard Tower".
     */
    public static boolean mentionsBuilding(String promise, String buildingName) {
        List<String> needed = words(buildingName).stream().filter(word -> !COMMON_WORDS.contains(word)).toList();
        if (needed.isEmpty()) {
            return false;
        }
        List<String> said = words(promise);
        return needed.stream().allMatch(word -> said.stream().anyMatch(s -> s.equals(word) || s.equals(word + "s")
                || s.equals(word + "es")));
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).replace("'s", "").split("[^\\p{L}]+"))
                .filter(word -> word.length() >= 3).toList();
    }

    /**
     * The colonist's promises as a prompt block: open ones with their deadline, those kept or broken
     * lately, and how each player keeps their word. Empty when there are none.
     */
    public static String promisesBlock(List<Promise> promises, long now) {
        if (promises.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        Map<String, int[]> record = new LinkedHashMap<>();
        List<String> recent = new ArrayList<>();
        boolean anyOpen = false;
        for (Promise promise : promises) {
            switch (promise.status()) {
                case OPEN -> {
                    anyOpen = true;
                    lines.add("- " + promise.text() + " (" + promise.makerName() + ", " + ago(now - promise.made())
                            + ", " + deadline(promise, now) + ")");
                }
                case KEPT, BROKEN -> {
                    boolean kept = promise.status() == Promise.Status.KEPT;
                    record.computeIfAbsent(promise.makerName(), name -> new int[2])[kept ? 0 : 1]++;
                    if (now - promise.resolved() <= RECENT_TICKS) {
                        recent.add(kept
                                ? "Recently kept: " + promise.text() + " You are grateful to " + promise.makerName() + "."
                                : "Recently broken: " + promise.text() + " You are disappointed in " + promise.makerName()
                                        + ".");
                    }
                }
                case CANCELLED -> {
                }
            }
        }
        if (lines.isEmpty() && recent.isEmpty() && record.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("Promises players made to you:");
        lines.forEach(line -> text.append('\n').append(line));
        recent.forEach(line -> text.append('\n').append(line));
        if (!record.isEmpty()) {
            text.append("\nKeeping their word to you: ").append(record.entrySet().stream().map(entry -> {
                int kept = entry.getValue()[0];
                int broken = entry.getValue()[1];
                String line = entry.getKey() + " kept " + kept + " and broke " + broken;
                if (broken > kept) {
                    return line + " (you are skeptical of their promises)";
                }
                return kept >= 2 && broken == 0 ? line + " (they keep their word)" : line;
            }).collect(Collectors.joining("; "))).append('.');
        }
        if (anyOpen) {
            text.append("\nRemind, thank or blame only the one who made a promise, and don't bring up someone's promise "
                    + "with anybody else unless they ask. While a promise is open and not overdue, don't complain about "
                    + "that problem: you trust them and wait.");
        }
        return text.toString();
    }

    /** Open promises numbered for the character model, which refers to them by number. */
    public static String numbered(List<Promise> open, long now) {
        if (open.isEmpty()) {
            return "(none)";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            Promise promise = open.get(i);
            String when = promise.due() == 0 ? "no deadline" : now > promise.due() ? "overdue" : dueIn(promise.due() - now);
            lines.add((i + 1) + ". " + promise.text() + " (" + when + ")");
        }
        return String.join("\n", lines);
    }

    /** How this player treats the colonist, setting the tone; empty without history. */
    public static String relationBlock(String player, Relation relation, long now) {
        if (relation.isEmpty()) {
            return "";
        }
        int rapport = relation.rapport();
        String tone;
        if (rapport >= 25) {
            tone = "consistently kind and respectful, so be warm and open with them and glad to help.";
        } else if (rapport >= 8) {
            tone = "mostly pleasant so far, so be friendly.";
        } else if (rapport <= -25) {
            tone = "often rude to you, so be cool and short with them; you still do what the colony's leaders ask, "
                    + "but without warmth, and you may mention how they behaved.";
        } else if (rapport <= -8) {
            tone = "unpleasant to you before, so be a bit guarded.";
        } else {
            tone = "nothing remarkable either way yet.";
        }
        StringBuilder text = new StringBuilder("How ").append(player).append(" treats you: ").append(tone);
        if (!relation.notes().isEmpty()) {
            text.append(" Moments you remember: ").append(relation.notes().stream()
                    .map(note -> note.text() + " (" + ago(now - note.time()) + ")").collect(Collectors.joining("; ")))
                    .append('.');
        }
        return text.toString();
    }

    /**
     * What the colony says about a player: manners, when several colonists agree, and how they keep
     * promises. Empty when there is nothing to say.
     *
     * @param rapports this player's rapport with each colonist who has a history with them
     */
    public static String reputation(String player, List<Integer> rapports, int kept, int broken) {
        List<String> parts = new ArrayList<>();
        if (rapports.size() >= 2) {
            double average = rapports.stream().mapToInt(Integer::intValue).average().orElse(0);
            if (average >= 15) {
                parts.add("Around the colony, " + player + " is known as kind and respectful.");
            } else if (average <= -15) {
                parts.add("Around the colony, " + player + " is known as rude: several colonists grumble about it.");
            }
        }
        if (kept + broken > 0) {
            parts.add(player + " has kept " + kept + " and broken " + broken + " promises to colonists.");
        }
        return String.join(" ", parts);
    }

    /**
     * How likely a colonist disagrees with someone they have a history with: more with those they
     * dislike, less with friends.
     */
    public static double contrarinessWith(double contrariness, int rapport) {
        return Math.clamp(contrariness - rapport * 0.006, 0, 0.9);
    }

    /** How much likelier a colonist answers someone they have strong feelings about, either way. */
    public static double replyWeight(int rapport) {
        return 1 + Math.min(1, Math.abs(rapport) / 50.0);
    }

    /** How a colonist gets along with another one, for the colony chat prompt; empty when it's nothing special. */
    public static String colonistLine(String other, Relation relation) {
        int rapport = relation.rapport();
        String tone;
        if (rapport >= 25) {
            tone = "you are good friends";
        } else if (rapport >= 8) {
            tone = "you get along well";
        } else if (rapport <= -25) {
            tone = "you can't stand each other";
        } else if (rapport <= -8) {
            tone = "there is friction between you";
        } else {
            return "";
        }
        String lately = relation.notes().isEmpty() ? ""
                : " (lately: " + relation.notes().get(relation.notes().size() - 1).text() + ")";
        return other + ": " + tone + lately;
    }

    private static String deadline(Promise promise, long now) {
        if (promise.due() == 0) {
            return "no deadline";
        }
        return now > promise.due() ? "was due " + ago(now - promise.due()) + ": overdue" : dueIn(promise.due() - now);
    }

    private static String dueIn(long ticks) {
        long days = (ticks + DAY - 1) / DAY;
        return days <= 0 ? "due today" : days == 1 ? "due in 1 day" : "due in " + days + " days";
    }

    private static String ago(long ticks) {
        long days = Math.max(0, ticks) / DAY;
        return days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
    }
}
