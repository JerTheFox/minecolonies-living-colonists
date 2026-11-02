package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;

/**
 * Topics with something at stake for colonists' own talk (#65), from the colony's life as code sees it: who can't stand
 * whom, who has become close, who has seemed unhappy. They sit beside the neutral topics, not instead of them, so the
 * colony stays a colony and not a reality show. For the model, in English.
 */
public final class Stakes {
    /** Two colonists and how they get on (rapport -100 to 100). */
    public record Pair(String first, String second, int rapport) {
    }

    /** From this rapport on, either way, a pair is worth talking about. */
    static final int NOTABLE = 40;

    private Stakes() {
    }

    /** What {@code speaker} could bring up; never gossip about themselves. */
    public static List<String> topics(String speaker, List<Pair> pairs, List<String> unhappy) {
        List<String> topics = new ArrayList<>();
        for (Pair pair : pairs) {
            boolean own = pair.first().equals(speaker) || pair.second().equals(speaker);
            String other = pair.first().equals(speaker) ? pair.second() : pair.first();
            if (pair.rapport() <= -NOTABLE) {
                topics.add(own ? "how " + other + " gets on your nerves lately"
                        : "how " + pair.first() + " and " + pair.second() + " can't stand each other lately");
            } else if (pair.rapport() >= NOTABLE && !own) {
                topics.add("how " + pair.first() + " and " + pair.second() + " have become close lately");
            }
        }
        for (String name : unhappy) {
            if (!name.equals(speaker)) {
                topics.add("how " + name + " has seemed unhappy lately, and what might be wrong");
            }
        }
        return topics;
    }
}
