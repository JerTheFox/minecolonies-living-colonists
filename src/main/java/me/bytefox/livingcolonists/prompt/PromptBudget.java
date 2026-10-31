package me.bytefox.livingcolonists.prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.bytefox.livingcolonists.debug.Counters;

/**
 * Keeps a reply prompt within a size (#40): as memory, events and chat grow in a big colony, the least
 * needed lines go first instead of the prompt and its cost growing without end. Remembered facts go
 * first from the end (the least relevant), then the oldest chat lines, then the far end of the
 * situation and the relations. Each section keeps its first line.
 */
public final class PromptBudget {
    /** About six thousand tokens of system prompt. */
    public static final int MAX_CHARS = 24000;

    private record Section(String key, boolean oldestFirst) {
    }

    private static final List<Section> ORDER = List.of(new Section("memories", false), new Section("transcript", true),
            new Section("situation", false), new Section("bonds", false), new Section("background", false));

    private PromptBudget() {
    }

    public static Map<String, String> fit(Map<String, String> values, String template) {
        return fit(values, template, MAX_CHARS);
    }

    /** The values, or a copy with sections cut down line by line until the rendered prompt fits {@code max}. */
    public static Map<String, String> fit(Map<String, String> values, String template, int max) {
        int length = length(values, template);
        if (length <= max) {
            return values;
        }
        Map<String, String> out = new HashMap<>(values);
        for (Section section : ORDER) {
            String text = out.get(section.key());
            if (text == null || !template.contains("{" + section.key() + "}")) {
                continue;
            }
            List<String> lines = new java.util.ArrayList<>(text.lines().toList());
            while (length > max && lines.size() > 1) {
                String gone = lines.remove(section.oldestFirst() ? 0 : lines.size() - 1);
                length -= gone.length() + 1;
            }
            out.put(section.key(), String.join("\n", lines));
            if (length <= max) {
                break;
            }
        }
        Counters.add("prompt.trimmed");
        return out;
    }

    /** About how long the rendered prompt is: the template plus the values it uses. */
    static int length(Map<String, String> values, String template) {
        int length = template.length();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (template.contains("{" + entry.getKey() + "}")) {
                length += entry.getValue().length();
            }
        }
        return length;
    }
}
