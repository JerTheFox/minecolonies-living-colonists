package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How item names in several languages are written into a prompt; free of game classes for unit tests. */
public final class ItemNameText {
    private static final int MAX_NAME = 64;

    private ItemNameText() {
    }

    /** "Coarse Dirt (in Russian: Каменистая земля)"; the English name alone when nothing else is known or differs. */
    public static String withTranslations(String english, Map<String, String> byLanguage) {
        List<String> others = new ArrayList<>();
        byLanguage.forEach((language, name) -> {
            if (!language.equals("English") && !name.equalsIgnoreCase(english)) {
                others.add("in " + language + ": " + name);
            }
        });
        return others.isEmpty() ? english : english + " (" + String.join("; ", others) + ")";
    }

    /** A name as a client sent it, fit to go into a prompt; empty if it isn't one. */
    public static String clean(String key, String name) {
        String text = name.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}\\[\\](){}<>|]", " ").replaceAll("\\s+", " ")
                .strip();
        return text.isEmpty() || text.equals(key) || text.length() > MAX_NAME ? "" : text;
    }
}
