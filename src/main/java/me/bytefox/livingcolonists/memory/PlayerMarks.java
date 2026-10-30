package me.bytefox.livingcolonists.memory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Players named in what colonists remember, kept by UUID: a fact says "⟦p:uuid|Максим|Максиму⟧" where the
 * model wrote "Максиму", and reads back as the player's current name. Unchanged, the words stay as the
 * model wrote them, case ending and all; after a rename (a nickname changed, {@code playerNames} set)
 * the new name takes their place, so one player never becomes two people in a colonist's memory.
 *
 * <p>Marks are read back where text leaves for the model ({@code ChatRequests}) and where it is shown
 * to people; the current names come from {@link #setCurrent}, any thread may read them.</p>
 */
public final class PlayerMarks {
    private static final Pattern MARK = Pattern.compile("⟦p:([0-9a-fA-F-]{36})\\|([^|⟧]*)\\|([^⟧]*)⟧");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}+");
    private static final int MIN_STEM = 3;
    private static final int MAX_ENDING = 3;
    private static final Map<UUID, String> CURRENT = new ConcurrentHashMap<>();

    private PlayerMarks() {
    }

    /** A mark for {@code form}, the words used, standing for the player known then as {@code name}. */
    public static String mark(UUID player, String name, String form) {
        return "⟦p:" + player + "|" + name.replace("|", "").replace("⟧", "") + "|" + form.replace("⟧", "") + "⟧";
    }

    /** The name a player goes by now. */
    public static void setCurrent(UUID player, String name) {
        CURRENT.put(player, name);
    }

    public static void clearCurrent() {
        CURRENT.clear();
    }

    /** The text with every mark read back as the player's current name. */
    public static String resolve(String text) {
        return resolve(text, CURRENT::get);
    }

    public static String resolve(String text, Function<UUID, String> current) {
        if (text == null || text.indexOf('⟦') < 0) {
            return text;
        }
        Matcher matcher = MARK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String now = current.apply(UUID.fromString(matcher.group(1)));
            String name = matcher.group(2);
            String form = matcher.group(3);
            String shown = now == null ? (form.isEmpty() ? "someone" : form)
                    : now.equals(name) && !form.isEmpty() ? form : now;
            matcher.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Marks every word that is one of these players' names or its case form ("Максиму" for Максим),
     * outside marks already there.
     *
     * @param names for each player, their names, the current one first
     */
    public static String markNames(String text, Map<UUID, ? extends Collection<String>> names) {
        if (names.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        Matcher marks = MARK.matcher(text);
        int at = 0;
        while (marks.find()) {
            out.append(markPlain(text.substring(at, marks.start()), names)).append(marks.group());
            at = marks.end();
        }
        return out.append(markPlain(text.substring(at), names)).toString();
    }

    private static String markPlain(String text, Map<UUID, ? extends Collection<String>> names) {
        Matcher words = WORD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (words.find()) {
            String word = words.group();
            String marked = null;
            for (Map.Entry<UUID, ? extends Collection<String>> player : names.entrySet()) {
                String current = player.getValue().stream().findFirst().orElse("");
                for (String name : player.getValue()) {
                    if (isForm(word, name)) {
                        marked = mark(player.getKey(), current, name.equals(current) ? word : current);
                        break;
                    }
                }
                if (marked != null) {
                    break;
                }
            }
            words.appendReplacement(out, Matcher.quoteReplacement(marked != null ? marked : word));
        }
        words.appendTail(out);
        return out.toString();
    }

    /** The word is the name, or for a Cyrillic name its form with a short case ending. */
    static boolean isForm(String word, String name) {
        String w = word.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        String n = name.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (n.isEmpty() || n.contains(" ")) {
            return false;
        }
        if (w.equals(n)) {
            return true;
        }
        if (!CYRILLIC.matcher(n).matches()) {
            return false;
        }
        String stem = n.replaceFirst("[аяоеиыуюйь]$", "");
        if (stem.length() < MIN_STEM || !w.startsWith(stem)) {
            return false;
        }
        String ending = w.substring(stem.length());
        return !ending.isEmpty() && ending.length() <= MAX_ENDING && CYRILLIC.matcher(ending).matches();
    }
}
