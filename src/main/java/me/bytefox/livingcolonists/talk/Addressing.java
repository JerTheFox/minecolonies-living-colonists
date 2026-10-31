package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds whom a message addresses, by code: names in any grammatical case ("Джону" finds Джон),
 * Latin names written in Cyrillic and back ("Адаир" finds Adair), and professions in the words of
 * the game's own translations. What this misses is left to the routing request to the model.
 */
public final class Addressing {
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}+");
    private static final Pattern OPEN_Y = Pattern.compile("(?<=[^aeiouy])y(?=[^aeiouy][aeiouy])");
    /** Case endings a Cyrillic name or profession takes after its stem. */
    private static final int MAX_ENDING = 3;
    /** A shorter stem is too likely to begin an ordinary word. */
    private static final int MIN_STEM = 3;
    /** Transliterated names this long may differ by one letter ("Браун" and Brown). */
    private static final int FUZZY_LENGTH = 5;
    private static final Map<Character, String> TO_LATIN = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('ґ', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('є', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"),
            Map.entry('и', "i"), Map.entry('і', "i"), Map.entry('ї', "i"), Map.entry('й', "i"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"),
            Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"), Map.entry('ў', "u"),
            Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"),
            Map.entry('щ', "sh"), Map.entry('ъ', ""), Map.entry('ы', "i"), Map.entry('ь', ""), Map.entry('э', "e"),
            Map.entry('ю', "iu"), Map.entry('я', "ia"));

    private static final Set<String> EVERYONE = Set.of("everyone", "everybody", "all", "guys", "folks", "people",
            "все", "всем", "всех", "вы", "вас", "вам", "народ", "ребята", "ребят");
    /**
     * Words for a building: a profession next to one names the building ("хижина строителя",
     * "builder's hut"), not its worker. Russian puts the profession after it, English before.
     */
    private static final List<String> BUILDING_BEFORE = List.of("хижин", "мастерск", "здани", "постройк", "дом",
            "башн", "лавк", "лачуг");
    private static final Set<String> BUILDING_AFTER = Set.of("hut", "huts", "house", "houses", "workshop", "building",
            "tower", "shop", "yard", "hall");

    private Addressing() {
    }

    /** Whether the message is plainly for everyone ("everyone", "guys", "все"), so nobody needs picking out. */
    public static boolean toEveryone(String message) {
        return words(message).stream().anyMatch(EVERYONE::contains);
    }

    /** "You" to one person ("ты", "тебе", "you"), not to several ("вы", "you guys"): ё and э read as е. */
    private static final Set<String> YOU_ONE = Set.of("ты", "тебя", "тебе", "тобой", "тобою", "твои", "твой",
            "твоя", "твое", "твоего", "твоей", "твоих", "твоим", "твоими", "твою", "you", "your", "yours", "yourself");

    /** Whether the message speaks to one person as "you", for a reply to whoever just spoke. */
    public static boolean secondPerson(String message) {
        return !toEveryone(message) && words(message).stream().anyMatch(YOU_ONE::contains);
    }

    /** The first colonist named in the message, like {@link #allMentioned}. */
    public static OptionalInt mentioned(String message, List<String> names) {
        List<Integer> all = allMentioned(message, names);
        return all.isEmpty() ? OptionalInt.empty() : OptionalInt.of(all.get(0));
    }

    /**
     * Indices of the colonists named in the message, in the order they come up: by first name, or
     * by a surname nobody else in the list has.
     */
    public static List<Integer> allMentioned(String message, List<String> names) {
        List<List<String>> callNames = new ArrayList<>();
        for (String name : names) {
            List<String> own = new ArrayList<>();
            List<String> parts = words(name);
            if (!parts.isEmpty()) {
                own.add(parts.get(0));
                String surname = parts.get(parts.size() - 1);
                if (parts.size() > 1 && surname.length() >= MIN_STEM
                        && names.stream().filter(other -> surname.equals(lastWord(other))).count() == 1) {
                    own.add(surname);
                }
            }
            callNames.add(own);
        }
        List<Integer> found = new ArrayList<>();
        for (String word : words(message)) {
            for (int i = 0; i < callNames.size(); i++) {
                if (!found.contains(i) && callNames.get(i).stream().anyMatch(name -> nameMatches(word, name))) {
                    found.add(i);
                }
            }
        }
        return found;
    }

    /** Whether a word of a message is one of these names, in any case or alphabet. */
    public static boolean isName(String word, Collection<String> names) {
        String w = normalize(word);
        return names.stream().flatMap(name -> words(name).stream()).anyMatch(part -> nameMatches(w, part));
    }

    /**
     * Indices of the colonists whose profession the message names, in list order.
     *
     * @param jobs for each colonist, the names of their profession (translations and common words);
     *             a name of several words needs all of them in the message
     */
    public static List<Integer> byJob(String message, List<? extends Collection<String>> jobs) {
        List<String> words = words(message);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            for (String job : jobs.get(i)) {
                List<String> parts = words(job);
                if (!parts.isEmpty() && parts.stream().allMatch(part -> callsProfession(words, part))) {
                    found.add(i);
                    break;
                }
            }
        }
        return found;
    }

    /** Whether some word of the message is this profession word and not part of a building's name. */
    private static boolean callsProfession(List<String> words, String part) {
        for (int i = 0; i < words.size(); i++) {
            if (wordMatches(words.get(i), part) && !namesBuilding(words, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesBuilding(List<String> words, int index) {
        if (index > 0) {
            String before = words.get(index - 1);
            if (BUILDING_BEFORE.stream().anyMatch(stem -> before.startsWith(stem)
                    && before.length() - stem.length() <= MAX_ENDING)) {
                return true;
            }
        }
        return index + 1 < words.size() && BUILDING_AFTER.contains(words.get(index + 1));
    }

    private static boolean nameMatches(String word, String name) {
        if (wordMatches(word, name)) {
            return true;
        }
        boolean wordCyrillic = CYRILLIC.matcher(word).find();
        if (wordCyrillic == CYRILLIC.matcher(name).find()) {
            return false;
        }
        String target = sound(name);
        if (target.length() < MIN_STEM) {
            return false;
        }
        if (target.length() < 4) {
            return sound(word).equals(target);
        }
        int limit = target.length() >= FUZZY_LENGTH ? 1 : 0;
        for (int cut = 0; cut <= (wordCyrillic ? 2 : 0) && cut < word.length(); cut++) {
            if (distance(sound(word.substring(0, word.length() - cut)), target) <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * A word of the message is this name or profession word, or its plural or case form: English
     * adds s or es, Cyrillic words change up to three letters after the stem.
     */
    private static boolean wordMatches(String word, String base) {
        if (word.equals(base)) {
            return true;
        }
        if (CYRILLIC.matcher(base).matches()) {
            String stem = base.replaceFirst("[аяоеёиыуюйь]$", "");
            if (stem.length() < MIN_STEM || !word.startsWith(stem)) {
                return false;
            }
            String ending = word.substring(stem.length());
            return !ending.isEmpty() && ending.length() <= MAX_ENDING && CYRILLIC.matcher(ending).matches();
        }
        return base.length() >= MIN_STEM && (word.equals(base + "s") || word.equals(base + "es"));
    }

    /** How a name sounds, in plain Latin letters: John, Джон and Dzhon all become "jon". */
    static String sound(String text) {
        StringBuilder latin = new StringBuilder();
        for (char c : text.toCharArray()) {
            latin.append(TO_LATIN.getOrDefault(c, String.valueOf(c)));
        }
        // An English y in an open syllable sounds "ai": Rylee is Райли, Kyle is Кайл, but Landyn is Лэндин.
        String s = OPEN_Y.matcher(latin).replaceAll("ai")
                .replace("dzh", "j").replace("dj", "j").replace("zh", "j")
                .replace("ph", "f").replace("th", "t").replace("ck", "k")
                .replace("sh", "\u0001").replace("ch", "\u0002")
                .replace("h", "")
                .replace("\u0001", "sh").replace("\u0002", "ch")
                .replace('c', 'k').replace('q', 'k').replace("x", "ks").replace('w', 'u').replace('y', 'i')
                .replace("ee", "i").replace("oo", "u");
        return s.replaceAll("(.)\\1+", "$1");
    }

    private static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            row[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = above;
            }
        }
        return row[b.length()];
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : WORDS.split(normalize(text))) {
            if (word.length() >= 2) {
                words.add(word);
            }
        }
        return words;
    }

    private static String lastWord(String name) {
        List<String> parts = words(name);
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }

    private static String normalize(String text) {
        // Russian writes э and е, ё and е for the same sound in names: Рэндольф is often рендольф.
        return text.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replace('э', 'е');
    }
}
