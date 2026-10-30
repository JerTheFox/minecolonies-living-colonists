package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One colonist's facts, oldest first. Server thread only. */
public final class FactBook {
    /** Share of words two facts about the same person need in common to be one fact told twice. */
    private static final double SAME_SUBJECT = 0.5;
    private final List<Fact> facts = new ArrayList<>();

    /**
     * Adds a fact. One the colonist already knows (same words, ignoring case and punctuation) is only
     * made as important and lasting as the new one. One about the same person on the same subject
     * ("Ephraim's knee aches", "Ephraim's knee still aches") is replaced by the newer words, keeping
     * the larger importance: retelling it doesn't make it weigh more. Returns whether it was new.
     */
    public boolean add(Fact fact) {
        String key = normalize(fact.text());
        Set<String> words = Digests.keywords(fact.text());
        for (int i = 0; i < facts.size(); i++) {
            Fact known = facts.get(i);
            if (normalize(known.text()).equals(key)) {
                facts.set(i, new Fact(known.text(), Math.max(known.importance(), fact.importance()), known.created(),
                        Math.max(known.expires(), fact.expires()), known.source(), known.told() || fact.told(), known.about()));
                return false;
            }
            if (!fact.about().isEmpty() && fact.about().equals(known.about())
                    && similarity(words, Digests.keywords(known.text())) >= SAME_SUBJECT) {
                facts.remove(i);
                facts.add(new Fact(fact.text(), Math.max(known.importance(), fact.importance()), fact.created(),
                        Math.max(known.expires(), fact.expires()), fact.source(), known.told() && fact.told(), fact.about()));
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

    /** Forgets the facts about someone (a colonist who died or left); returns whether any went. */
    public boolean forgetAbout(String about) {
        return facts.removeIf(fact -> fact.about().equals(about));
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

    private static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> either = new HashSet<>(a);
        either.addAll(b);
        return (double) both.size() / either.size();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
