package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds a thing (an item, a building) by the words of a player or a model, by code: the model passes
 * the words ("oak planks", "железо"), code finds what they name among the game's own names and
 * translations. When several things fit equally ("planks"), it says which, so the colonist can ask.
 */
public final class ItemMatch {
    /** A thing that can be found, with all its names (translations included). */
    public record Candidate<T>(T value, String id, Collection<String> names) {
    }

    public sealed interface Result<T> {
        record Found<T>(T value) implements Result<T> {
        }

        /** Several things fit equally well; up to {@link #MAX_OPTIONS} of them, best first. */
        record Ambiguous<T>(List<Candidate<T>> options) implements Result<T> {
        }

        record None<T>() implements Result<T> {
        }
    }

    public static final int MAX_OPTIONS = 5;
    private static final int EXACT = 100;
    /** Below this a match is a guess, not a find. */
    private static final int ENOUGH = 60;
    private static final Set<String> STOP = Set.of("of", "the", "a", "an", "some");

    private ItemMatch() {
    }

    public static <T> Result<T> find(String query, Collection<Candidate<T>> candidates) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            return new Result.None<>();
        }
        record Scored<T>(Candidate<T> candidate, int score, String name) {
        }
        List<Scored<T>> scored = new ArrayList<>();
        for (Candidate<T> candidate : candidates) {
            int best = 0;
            String bestName = "";
            for (String name : names(candidate)) {
                int score = score(words, words(name));
                if (score > best || score == best && name.length() < bestName.length()) {
                    best = score;
                    bestName = name;
                }
            }
            if (best >= ENOUGH) {
                scored.add(new Scored<>(candidate, best, bestName));
            }
        }
        if (scored.isEmpty()) {
            return new Result.None<>();
        }
        scored.sort(Comparator.<Scored<T>>comparingInt(s -> -s.score())
                .thenComparingInt(s -> namespaceRank(s.candidate().id()))
                .thenComparingInt(s -> s.name().length()));
        Scored<T> top = scored.get(0);
        List<Scored<T>> tied = scored.stream().filter(s -> s.score() == top.score()).toList();
        if (top.score() == EXACT || tied.size() == 1) {
            return new Result.Found<>(top.candidate().value());
        }
        return new Result.Ambiguous<>(tied.stream().limit(MAX_OPTIONS).map(Scored::candidate).toList());
    }

    /**
     * 100 for the same words; for every query word found in the name, less for each extra word of
     * the name; a name that only shares some of the words scores by the share, and less than that.
     */
    static int score(List<String> query, List<String> name) {
        if (name.isEmpty()) {
            return 0;
        }
        int found = 0;
        for (String word : query) {
            if (name.stream().anyMatch(other -> sameWord(word, other))) {
                found++;
            }
        }
        if (found == query.size()) {
            int extra = Math.max(0, name.size() - query.size());
            return extra == 0 && query.size() == name.size() ? EXACT : Math.max(ENOUGH, 90 - 10 * extra);
        }
        return found * 50 / query.size();
    }

    /** The same word, or its plural or case form: "planks" and "plank", "доски" and "доска". */
    static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String stemA = stem(a);
        String stemB = stem(b);
        return stemA.length() >= 3 && stemA.equals(stemB);
    }

    private static String stem(String word) {
        if (word.matches("\\p{IsCyrillic}+")) {
            return word.replaceFirst("(ами|ями|ых|их|ов|ев|ей|ам|ям|ах|ях|ой|ый|ий|ая|яя|ое|ее|ые|ие|ом|ем|а|я|ы|и|у|ю|е|о|ь|й)$", "");
        }
        return word.replaceFirst("(ies)$", "y").replaceFirst("(es|s)$", "");
    }

    private static Collection<String> names(Candidate<?> candidate) {
        List<String> names = new ArrayList<>(candidate.names());
        String path = candidate.id().substring(candidate.id().indexOf(':') + 1);
        names.add(path.replace('_', ' '));
        return names;
    }

    /** Vanilla and MineColonies first: a colony uses their items unless a mod is named. */
    private static int namespaceRank(String id) {
        if (id.startsWith("minecraft:")) {
            return 0;
        }
        return id.startsWith("minecolonies:") ? 1 : 2;
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty() && !STOP.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }
}
