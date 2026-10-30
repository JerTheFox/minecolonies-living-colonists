package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines that go round in circles, told by code: a line that says again what was said lately, or one
 * more line about the same thing (the knee, nowhere to sleep) after several. Asked "not to repeat
 * themselves", models still do; code catches it and asks for another line. Free of game classes for
 * unit tests.
 */
public final class Repeats {
    /** Share of words in common that makes a line a repeat of one said lately. */
    static final double TOO_CLOSE = 0.5;
    /** A word in this many of the recent lines is a motif going round. */
    static final int MOTIF = 3;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** Words too common to be a subject, in the languages colonists speak most. */
    private static final Set<String> COMMON = Set.of(
            "about", "after", "again", "also", "been", "before", "being", "could", "does", "done", "from", "have",
            "into", "just", "like", "made", "make", "only", "said", "should", "some", "than", "that", "their", "them",
            "then", "there", "they", "this", "told", "very", "were", "what", "when", "will", "with", "would", "your",
            "here", "today", "yeah", "well", "know", "think", "still", "really",
            "этот", "этого", "этом", "тоже", "сегодня", "только", "когда", "если", "потом", "сейчас", "здесь", "очень",
            "может", "будет", "было", "были", "есть", "всех", "чтобы", "просто", "ладно", "вообще", "который", "которая",
            "такой", "такая", "так", "тут", "там", "меня", "тебя", "тебе", "мне", "себя", "него", "неё",
            "хотя", "пока", "ещё", "еще", "уже", "даже", "вот", "надо", "нужно", "знаю", "думаю", "кажется", "раз",
            "всё", "все", "весь", "день", "сам", "сама", "свой", "своя", "своё", "наш", "наша", "ваш", "лишь");

    private Repeats() {
    }

    /**
     * What is wrong with the line, if it goes round in circles, said for the model's second try.
     *
     * @param recent the latest lines heard, newest last
     * @param names  people's names, which come up often without being a subject
     * @param motifs also count words going round in several lines; off for an answer to a player,
     *               who sets the subject themselves
     */
    public static Optional<String> problem(String line, List<String> recent, Collection<String> names, boolean motifs) {
        Map<String, String> words = words(line, names);
        if (words.size() < 2) {
            return Optional.empty();
        }
        for (String said : recent) {
            Set<String> other = words(said, names).keySet();
            if (similarity(words.keySet(), other) >= TOO_CLOSE) {
                return Optional.of("it says again what was said lately: \"" + said + "\"");
            }
        }
        if (motifs) {
            List<String> going = new ArrayList<>();
            words.forEach((stem, word) -> {
                long times = recent.stream().filter(said -> words(said, names).containsKey(stem)).count();
                if (times >= MOTIF) {
                    going.add(word);
                }
            });
            if (!going.isEmpty()) {
                return Optional.of("it comes back once more to what has come up again and again lately ("
                        + String.join(", ", going) + ")");
            }
        }
        return Optional.empty();
    }

    /** The words that say what a line is about, by stem (the first five letters), with the word itself. */
    static Map<String, String> words(String text, Collection<String> names) {
        Set<String> nameStems = new HashSet<>();
        for (String name : names) {
            Matcher m = WORD.matcher(name.toLowerCase(Locale.ROOT));
            while (m.find()) {
                nameStems.add(stem(m.group()));
            }
        }
        Map<String, String> words = new LinkedHashMap<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        while (m.find()) {
            String word = m.group();
            if (word.length() >= 4 && !COMMON.contains(word) && !nameStems.contains(stem(word))) {
                words.putIfAbsent(stem(word), word);
            }
        }
        return words;
    }

    private static String stem(String word) {
        return word.length() > 5 ? word.substring(0, 5) : word;
    }

    private static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> either = new HashSet<>(a);
        either.addAll(b);
        return (double) both.size() / either.size();
    }
}
