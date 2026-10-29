package me.bytefox.livingcolonists.talk;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Finds which colonist a message is addressed to by name. */
public final class Addressing {
    private Addressing() {
    }

    /**
     * The index of the first name in {@code names} whose first word appears in the message as a
     * whole word, ignoring case: "John, bring coal" matches "John Brown".
     */
    public static OptionalInt mentioned(String message, List<String> names) {
        String text = message.toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.size(); i++) {
            String first = names.get(i).strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (first.length() >= 2
                    && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(first) + "(?![\\p{L}\\p{N}])").matcher(text).find()) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }
}
