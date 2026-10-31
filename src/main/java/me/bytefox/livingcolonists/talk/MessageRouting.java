package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Whom a player's colony message is for, as far as code can tell (#44, #45): colonists named, then those
 * of a profession named, then whoever just spoke when the message says "you" without a name. A message
 * for everyone without a question needs nobody in particular; anything else goes to the router model.
 */
final class MessageRouting {
    private MessageRouting() {
    }

    /**
     * @param names        the colonists' names
     * @param jobs         each colonist's profession words
     * @param justSpoke    index of the colonist whose line came just before, or -1
     * @return the indices found by code, at most {@code max}; an empty list for everyone; empty for the router
     */
    static Optional<List<Integer>> byCode(String message, List<String> names, List<? extends Collection<String>> jobs,
                                          int justSpoke, int max, Random random) {
        List<Integer> byJob = new ArrayList<>(Addressing.byJob(message, jobs));
        Collections.shuffle(byJob, random);
        List<Integer> chosen = new ArrayList<>(Addressing.allMentioned(message, names));
        byJob.stream().filter(index -> !chosen.contains(index)).forEach(chosen::add);
        if (chosen.isEmpty() && justSpoke >= 0 && Addressing.secondPerson(message)) {
            // "You first make the ground floor livable" answers whoever just spoke, not the router's guess.
            chosen.add(justSpoke);
        }
        // A question to everyone still goes to the router: it may be about one profession's work.
        if (!chosen.isEmpty() || Addressing.toEveryone(message) && !message.contains("?")) {
            return Optional.of(chosen.stream().limit(max).toList());
        }
        return Optional.empty();
    }
}
