package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.prompt.Templates;

/**
 * Works out whom a message to the whole colony addresses, by name or by profession, in any
 * language, with a short request to the model. {@link ColonyThreads} lets them answer first.
 */
public final class ColonyRouter {
    public record Member(String name, String job) {
    }

    private ColonyRouter() {
    }

    /** @param template the {@code route-colony.txt} prompt, with a {@code {roster}} placeholder */
    public static List<ChatMessage> prompt(String template, List<Member> roster, String message) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < roster.size(); i++) {
            lines.append(i + 1).append(". ").append(roster.get(i).name()).append(" — ").append(roster.get(i).job()).append('\n');
        }
        return List.of(ChatMessage.system(Templates.render(template, Map.of("roster", lines.toString().strip()))),
                ChatMessage.user(message));
    }

    /**
     * @return zero-based indices of the addressed colonists, or an empty list for "everyone" and
     * for anything that can't be understood
     */
    public static List<Integer> parse(String reply, int rosterSize) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonElement to = JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonObject().get("to");
            if (to == null || !to.isJsonArray()) {
                return List.of();
            }
            JsonArray array = to.getAsJsonArray();
            List<Integer> indices = new ArrayList<>();
            for (JsonElement element : array) {
                int index = element.getAsInt() - 1;
                if (index < 0 || index >= rosterSize) {
                    return List.of();
                }
                indices.add(index);
            }
            return indices;
        } catch (JsonParseException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
            return List.of();
        }
    }
}
