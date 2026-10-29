package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final Pattern PROMISE = Pattern.compile(
            "^\\s*[-*•]?\\s*promise\\s*\\|\\s*(\\d{1,3})\\s*\\|\\s*([A-Za-z]+)\\s*\\|\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLUTION = Pattern.compile(
            "^\\s*[-*•]?\\s*(kept|broken|cancelled)\\s*\\|\\s*#?(\\d{1,2})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDUCT = Pattern.compile(
            "^\\s*[-*•]?\\s*(kind|rude)\\s*\\|\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    /** New promises and conduct notes taken from one conversation at most. */
    private static final int MAX_PROMISES = 3;
    private static final int MAX_CONDUCT = 2;

    /** @param importance 1 to 5 */
    public record Entry(int importance, String text) {
    }

    /** @param days deadline in days from now, 0 without one */
    public record NewPromise(int days, String about, String text) {
    }

    /** @param kind "kept", "broken" or "cancelled"; @param number the open promise's number, from 1 */
    public record Resolution(String kind, int number) {
    }

    public record Conduct(boolean kind, String note) {
    }

    /** Everything the character model wrote down about a conversation. */
    public record Digest(List<Entry> facts, List<NewPromise> promises, List<Resolution> resolutions,
                         List<Conduct> conduct) {
    }

    private Digests() {
    }

    /** Lines like {@code 3 | Max promised bread}; anything else, like {@code [nothing]}, is skipped. */
    public static List<Entry> parse(String text, int max) {
        return parseAll(text, max).facts();
    }

    /**
     * Facts ({@code 3 | ...}), new promises ({@code promise | days | topic | ...}), promises resolved
     * ({@code kept | 2}) and conduct ({@code kind | ...}, {@code rude | ...}); other lines are skipped.
     */
    public static Digest parseAll(String text, int maxFacts) {
        List<Entry> facts = new ArrayList<>();
        List<NewPromise> promises = new ArrayList<>();
        List<Resolution> resolutions = new ArrayList<>();
        List<Conduct> conduct = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher matcher;
            if ((matcher = LINE.matcher(line)).matches()) {
                if (facts.size() < maxFacts) {
                    int importance = Math.max(1, Math.min(5, Integer.parseInt(matcher.group(1))));
                    facts.add(new Entry(importance, matcher.group(2)));
                }
            } else if ((matcher = PROMISE.matcher(line)).matches()) {
                if (promises.size() < MAX_PROMISES) {
                    promises.add(new NewPromise(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.group(3)));
                }
            } else if ((matcher = RESOLUTION.matcher(line)).matches()) {
                resolutions.add(new Resolution(matcher.group(1).toLowerCase(Locale.ROOT),
                        Integer.parseInt(matcher.group(2))));
            } else if ((matcher = CONDUCT.matcher(line)).matches()) {
                if (conduct.size() < MAX_CONDUCT) {
                    conduct.add(new Conduct(matcher.group(1).equalsIgnoreCase("kind"), matcher.group(2)));
                }
            }
        }
        return new Digest(facts, promises, resolutions, conduct);
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
