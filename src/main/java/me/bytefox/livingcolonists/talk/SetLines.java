package me.bytefox.livingcolonists.talk;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonParser;

/**
 * What colonists say without a language model (#68): set lines from the mod's language files, by occasion
 * ({@code livingcolonists.line.<occasion>.<n>}), in the listener's language, English when it has none. Read from the
 * mod's own jar, so a dedicated server has them too.
 */
public final class SetLines {
    private static final String PREFIX = "livingcolonists.line.";
    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

    private SetLines() {
    }

    /** A line for the occasion, picked by the dice, with its {@code %s} filled in; empty if the occasion has none. */
    public static Optional<String> pick(String occasion, String locale, Random random, Object... args) {
        Map<String, String> lines = lines(locale == null ? "en_us" : locale.toLowerCase(Locale.ROOT));
        int count = count(lines, occasion);
        if (count == 0 && !"en_us".equals(locale)) {
            lines = lines("en_us");
            count = count(lines, occasion);
        }
        if (count == 0) {
            return Optional.empty();
        }
        String line = lines.get(PREFIX + occasion + "." + (1 + random.nextInt(count)));
        try {
            return Optional.of(args.length == 0 ? line : String.format(Locale.ROOT, line, args));
        } catch (java.util.IllegalFormatException e) {
            return Optional.of(line);
        }
    }

    /** The same in a voice ({@link me.bytefox.livingcolonists.social.Voices}): its own lines first, else the plain ones. */
    public static Optional<String> pick(String occasion, String voice, String locale, Random random, Object... args) {
        if (voice != null && !voice.isEmpty()) {
            Optional<String> voiced = pick(occasion + "." + voice, locale, random, args);
            if (voiced.isPresent()) {
                return voiced;
            }
        }
        return pick(occasion, locale, random, args);
    }

    /** The translation key of a line in a voice, else a plain one. */
    public static Optional<String> key(String occasion, String voice, Random random) {
        if (voice != null && !voice.isEmpty()) {
            Optional<String> voiced = key(occasion + "." + voice, random);
            if (voiced.isPresent()) {
                return voiced;
            }
        }
        return key(occasion, random);
    }

    /** Two colonists' small talk on a topic (#68): a line and its answer, in the locale; empty if the topic has none. */
    public static Optional<String[]> pair(String topic, String locale, Random random) {
        Map<String, String> lines = lines(locale == null ? "en_us" : locale.toLowerCase(Locale.ROOT));
        if (count(lines, "pair." + topic) == 0) {
            lines = lines("en_us");
        }
        int count = count(lines, "pair." + topic);
        if (count == 0) {
            return Optional.empty();
        }
        String key = PREFIX + "pair." + topic + "." + (1 + random.nextInt(count));
        String reply = lines.get(key + ".reply");
        return reply == null ? Optional.empty() : Optional.of(new String[] {lines.get(key), reply});
    }

    /** The translation key of one of the occasion's lines, picked by the dice: for a line each player reads in their language. */
    public static Optional<String> key(String occasion, Random random) {
        int count = count(lines("en_us"), occasion);
        return count == 0 ? Optional.empty() : Optional.of(PREFIX + occasion + "." + (1 + random.nextInt(count)));
    }

    public static boolean has(String occasion) {
        return count(lines("en_us"), occasion) > 0;
    }

    private static int count(Map<String, String> lines, String occasion) {
        int n = 0;
        while (lines.containsKey(PREFIX + occasion + "." + (n + 1))) {
            n++;
        }
        return n;
    }

    private static Map<String, String> lines(String locale) {
        return LANGUAGES.computeIfAbsent(locale, SetLines::read);
    }

    private static Map<String, String> read(String locale) {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = SetLines.class.getResourceAsStream("/assets/livingcolonists/lang/" + locale + ".json")) {
            if (in != null) {
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()
                        .forEach(e -> {
                            if (e.getKey().startsWith(PREFIX)) {
                                out.put(e.getKey(), e.getValue().getAsString());
                            }
                        });
            }
        } catch (IOException | RuntimeException e) {
            // A language without lines falls back to English.
        }
        return out;
    }
}
