package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.llm.ChatMessage;

/**
 * Decides which colonists answer a message to the whole colony. A short request asks the model
 * whom the message addresses (by name or by profession, in any language); with nobody in
 * particular, two or three random colonists answer.
 */
public final class ColonyRouter {
    /** At most this many colonists answer one message. */
    public static final int MAX_RESPONDERS = 3;

    public record Member(String name, String job) {
    }

    private ColonyRouter() {
    }

    public static List<ChatMessage> prompt(List<Member> roster, String message) {
        StringBuilder system = new StringBuilder("""
                A player wrote a chat message to a whole MineColonies colony. Decide which colonists it is addressed to.
                Colonists:
                """);
        for (int i = 0; i < roster.size(); i++) {
            system.append(i + 1).append(". ").append(roster.get(i).name()).append(" — ").append(roster.get(i).job()).append('\n');
        }
        system.append("""
                Answer with JSON only.
                If the message names colonists or a profession (for example "builders" means every builder), \
                answer {"to": [numbers of those colonists]}.
                If it is for everyone or for nobody in particular, answer {"to": "everyone"}.
                The message may be in any language.""");
        return List.of(ChatMessage.system(system.toString()), ChatMessage.user(message));
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

    /**
     * The colonists who actually answer: the addressed ones (at most {@link #MAX_RESPONDERS}), or
     * two or three at random when nobody in particular was addressed.
     */
    public static List<Integer> choose(List<Integer> addressed, int rosterSize, Random random) {
        if (!addressed.isEmpty()) {
            Set<Integer> unique = new LinkedHashSet<>(addressed);
            return unique.stream().limit(MAX_RESPONDERS).toList();
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < rosterSize; i++) {
            all.add(i);
        }
        Collections.shuffle(all, random);
        int count = Math.min(rosterSize, 2 + random.nextInt(2));
        return List.copyOf(all.subList(0, count));
    }
}
