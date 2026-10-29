package me.bytefox.livingcolonists.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {@code {placeholders}} in prompt templates. */
public final class Templates {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private Templates() {
    }

    /**
     * Replaces known placeholders; unknown ones stay as they are, so a typo in a user's template is
     * visible in {@code /lcdebug prompt}. Lines that end up blank (a placeholder with nothing in it)
     * are dropped.
     */
    public static String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString().replaceAll("(?m)^[ \\t]*\\R", "").strip();
    }
}
