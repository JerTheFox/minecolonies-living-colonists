package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** One colonist's facts, oldest first. Server thread only. */
public final class FactBook {
    private final List<Fact> facts = new ArrayList<>();

    /**
     * Adds a fact; one the colonist already knows (same words, ignoring case and punctuation) is
     * only made as important and lasting as the new one. Returns whether it was new.
     */
    public boolean add(Fact fact) {
        String key = normalize(fact.text());
        for (int i = 0; i < facts.size(); i++) {
            Fact known = facts.get(i);
            if (normalize(known.text()).equals(key)) {
                facts.set(i, new Fact(known.text(), Math.max(known.importance(), fact.importance()), known.created(),
                        Math.max(known.expires(), fact.expires()), known.source(), known.told() || fact.told()));
                return false;
            }
        }
        facts.add(fact);
        return true;
    }

    /** Forgets expired facts, then the least important (the oldest of equals) over {@code max}. */
    public void cleanup(long now, int max) {
        facts.removeIf(fact -> fact.expires() < now);
        while (facts.size() > max) {
            facts.remove(facts.stream()
                    .min(Comparator.comparingInt(Fact::importance).thenComparingLong(Fact::created))
                    .orElseThrow());
        }
    }

    /** Marks a fact as passed on; false if the colonist no longer remembers it. */
    public boolean markTold(Fact fact) {
        int index = facts.indexOf(fact);
        if (index < 0) {
            return false;
        }
        facts.set(index, fact.asTold());
        return true;
    }

    public List<Fact> all() {
        return List.copyOf(facts);
    }

    public boolean isEmpty() {
        return facts.isEmpty();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
