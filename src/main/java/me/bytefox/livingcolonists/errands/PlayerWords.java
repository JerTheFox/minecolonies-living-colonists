package me.bytefox.livingcolonists.errands;

import java.util.List;

/**
 * Whether an order comes from the player's own words, not from the model's imagination: the words
 * the model quotes for it have to be in what the player recently wrote. Idea from Talking Colonists
 * (sshcrack, {@code broadcast/PlayerWords.java}), written anew.
 */
public final class PlayerWords {
    private PlayerWords() {
    }

    /**
     * @param quote the player's words the model gives for the order
     * @param lines what the player wrote lately, newest last
     * @return true when at least half of the quote's words, and at least one, are in those lines
     */
    public static boolean grounded(String quote, List<String> lines) {
        List<String> words = ItemMatch.words(quote).stream().filter(word -> word.length() >= 3).toList();
        if (words.isEmpty()) {
            return false;
        }
        List<String> said = lines.stream().flatMap(line -> ItemMatch.words(line).stream()).toList();
        long found = words.stream().filter(word -> said.stream().anyMatch(other -> ItemMatch.sameWord(word, other))).count();
        return found * 2 >= words.size();
    }
}
