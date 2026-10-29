package me.bytefox.livingcolonists.talk;

import java.lang.Character.UnicodeScript;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out the language a colonist should answer in, by code: the alphabet of the player's
 * message, with the game locale saying which language of that alphabet. The model only gets the
 * plain answer ("Russian"), so notes in another language no longer pull the reply off.
 */
public final class Languages {
    /** Fewer letters than this say nothing about the language. */
    private static final int MIN_LETTERS = 3;
    /** The share of letters one alphabet needs to decide. */
    private static final double DOMINANT = 0.7;
    /** Words that only English among the Latin-alphabet languages uses this often. */
    private static final Set<String> ENGLISH = Set.of("the", "you", "what", "how", "are", "and", "hello", "hi", "hey",
            "your", "have", "can", "where", "why", "who", "this", "that", "with", "not", "yes", "please", "thanks",
            "our", "it");
    private static final Map<UnicodeScript, String> DEFAULTS = new EnumMap<>(Map.of(
            UnicodeScript.CYRILLIC, "Russian",
            UnicodeScript.HAN, "Chinese",
            UnicodeScript.HANGUL, "Korean",
            UnicodeScript.GREEK, "Greek",
            UnicodeScript.ARABIC, "Arabic",
            UnicodeScript.HEBREW, "Hebrew",
            UnicodeScript.THAI, "Thai"));

    private Languages() {
    }

    /** The English name of a game locale's language: {@code ru_ru} is Russian; English if unknown. */
    public static String ofLocale(String locale) {
        String code = languageCode(locale);
        String name = code.isEmpty() ? "" : Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() || name.equalsIgnoreCase(code) ? "English" : name;
    }

    /**
     * The language to answer a message in.
     *
     * @param names    colonists' names: written in another alphabet, they don't count
     * @param previous the language of the talk so far, for messages too short to tell
     * @return empty when the code can't be sure (another Latin-alphabet language than English, or
     * mixed alphabets): the prompt then leaves it to the model
     */
    public static Optional<String> reply(String message, String locale, Collection<String> names, Optional<String> previous) {
        Map<UnicodeScript, Integer> letters = new EnumMap<>(UnicodeScript.class);
        boolean kana = false;
        boolean english = false;
        int total = 0;
        for (String word : message.split("[^\\p{L}\\p{M}]+")) {
            if (word.isEmpty() || Addressing.isName(word, names)) {
                continue;
            }
            english |= ENGLISH.contains(word.toLowerCase(Locale.ROOT));
            for (int i = 0; i < word.length(); ) {
                int c = word.codePointAt(i);
                i += Character.charCount(c);
                if (!Character.isLetter(c)) {
                    continue;
                }
                UnicodeScript script = UnicodeScript.of(c);
                if (script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA) {
                    kana = true;
                    script = UnicodeScript.HAN;
                }
                letters.merge(script, 1, Integer::sum);
                total++;
            }
        }
        if (total < MIN_LETTERS) {
            return previous.isPresent() ? previous : Optional.of(ofLocale(locale));
        }
        Map.Entry<UnicodeScript, Integer> top = letters.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        if (top.getValue() < DOMINANT * total) {
            return Optional.empty();
        }
        UnicodeScript script = top.getKey();
        if (script == UnicodeScript.LATIN) {
            return english || languageCode(locale).equals("en") ? Optional.of("English") : Optional.empty();
        }
        if (kana) {
            return Optional.of("Japanese");
        }
        if (script == scriptOf(locale)) {
            return Optional.of(ofLocale(locale));
        }
        return Optional.ofNullable(DEFAULTS.get(script));
    }

    /** The alphabet a locale's language is written in, read from the language's own name for itself. */
    private static UnicodeScript scriptOf(String locale) {
        Locale language = Locale.forLanguageTag(languageCode(locale));
        String own = language.getDisplayLanguage(language);
        return own.codePoints().filter(Character::isLetter).mapToObj(UnicodeScript::of).findFirst()
                .orElse(UnicodeScript.LATIN);
    }

    private static String languageCode(String locale) {
        return locale == null ? "" : locale.split("[_-]")[0].toLowerCase(Locale.ROOT);
    }
}
