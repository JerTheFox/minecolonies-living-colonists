package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading the character model's notes on a conversation, and turning facts back into prompt text. */
public final class Digests {
    /** Longest fact kept, in characters. */
    public static final int MAX_FACT_LENGTH = 240;
    private static final long DAY = 24000;
    /** The "(secret)" mark at the end of a fact, with the full stop after it. */
    private static final Pattern SECRET_MARK = Pattern.compile("\\s*\\(secret\\)\\s*[.!]?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE = Pattern.compile("^\\s*[-*•]?\\s*(\\d{1,3})\\s*\\|\\s*(.+?)\\s*$");

    /** @param importance 1 to 5 */
    public record Entry(int importance, String text) {
    }

    private Digests() {
    }

    /** Lines like {@code 3 | Max promised bread}; anything else, like {@code [nothing]}, is skipped. */
    public static List<Entry> parse(String text, int max) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = LINE.matcher(line);
            if (entries.size() < max && matcher.matches()) {
                int importance = Math.max(1, Math.min(5, Integer.parseInt(matcher.group(1))));
                entries.add(new Entry(importance, matcher.group(2)));
            }
        }
        return entries;
    }

    /** The facts as a prompt block, oldest first, with when and where each was heard; empty without facts. */
    public static String memories(List<Fact> facts, long now) {
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("Things you remember from earlier talks. They are background: bring "
                + "one up only when the talk touches it, and never recite them:");
        for (Fact fact : facts) {
            String said = fact.text();
            String where;
            if (fact.isPrivate() && isSecret(fact)) {
                said = SECRET_MARK.matcher(said).replaceAll("");
                said = said.matches(".*[.!?]$") ? said : said + ".";
                where = fact.teller() + " told you in secret: keep it to yourself, whoever asks, unless " + fact.teller()
                        + " brings it up";
            } else {
                where = fact.isPrivate() ? fact.teller() + " told you privately" : "said in the colony chat";
            }
            text.append("\n- ").append(said).append(" (").append(where).append(", ")
                    .append(ago(now - fact.created())).append(')');
        }
        return text.toString();
    }

    /**
     * Something worth passing on as gossip: heard in someone's private talk, not trivia, not told yet,
     * and not from {@code listener}, who knows it already. Weighted by importance.
     */
    public static Optional<Fact> pickRumor(List<Fact> facts, String listener, Random random) {
        List<Fact> eligible = facts.stream()
                .filter(fact -> fact.isPrivate() && !fact.told() && fact.importance() >= 2
                        && !fact.teller().equals(listener) && !isSecret(fact))
                .toList();
        int total = eligible.stream().mapToInt(Fact::importance).sum();
        if (total == 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Fact fact : eligible) {
            roll -= fact.importance();
            if (roll < 0) {
                return Optional.of(fact);
            }
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }

    /** One line of plain text, without formatting codes, within {@link #MAX_FACT_LENGTH}. */
    public static String cleanFact(String text) {
        String fact = text.replaceAll("§.?", "").replaceAll("\\s+", " ").strip();
        return fact.length() > MAX_FACT_LENGTH ? fact.substring(0, MAX_FACT_LENGTH - 1) + "…" : fact;
    }

    /** The character model marks what someone asked to keep quiet with "(secret)". */
    private static boolean isSecret(Fact fact) {
        return SECRET_MARK.matcher(fact.text()).find();
    }

    private static String ago(long ticks) {
        long days = Math.max(0, ticks) / DAY;
        return days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
    }
}
