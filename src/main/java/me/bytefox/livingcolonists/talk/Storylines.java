package me.bytefox.livingcolonists.talk;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import me.bytefox.livingcolonists.bonds.Relation;

/**
 * A colonist's running story (#65): their strongest tie to another colonist that has a history, a feud or a closeness.
 * Now and then their own talk carries it on from where it was, so a talk doesn't start from nothing every time. The
 * moments in it are the ones the relations already keep, written down from the colony chat.
 */
public final class Storylines {
    /** @param topic what the colonist is asked to bring up, for the model, in English */
    public record Story(String other, String topic) {
    }

    /** A tie this strong (of 100), either way, with something that happened in it, is a story. */
    static final int STRONG = 25;

    private Storylines() {
    }

    /** The story to carry on, from the colonist's ties by the other's name; empty when there is none. */
    public static Optional<Story> story(Map<String, Relation> ties) {
        return ties.entrySet().stream()
                .filter(tie -> Math.abs(tie.getValue().rapport()) >= STRONG && !tie.getValue().notes().isEmpty())
                .max(Comparator.comparingInt(tie -> Math.abs(tie.getValue().rapport())))
                .map(tie -> {
                    Relation relation = tie.getValue();
                    String latest = relation.notes().get(relation.notes().size() - 1).text();
                    String bond = relation.rapport() > 0 ? "you and " + tie.getKey() + " have grown close"
                            : "you and " + tie.getKey() + " can't stand each other";
                    return new Story(tie.getKey(), "to carry on the story between you and " + tie.getKey() + ": " + bond
                            + "; the latest was that " + latest + ". Bring up something new in it, true to what happened"
                            + " so far, and turn to " + tie.getKey() + " by name.");
                });
    }
}
