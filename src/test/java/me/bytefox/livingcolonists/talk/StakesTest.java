package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Topics with something at stake for colonists' own talk (#65): who quarrels, who is close, who is unhappy. */
class StakesTest {
    private static final List<Stakes.Pair> PAIRS = List.of(
            new Stakes.Pair("Darcy Abbott", "Hunter Casey", -50),
            new Stakes.Pair("Fiona Lowe", "Hope McNamara", 45),
            new Stakes.Pair("Ginny Blackwood", "Darcy Abbott", 10));

    @Test
    void quarrelsFriendshipsAndUnhappinessAreTopics() {
        List<String> topics = Stakes.topics("Ginny Blackwood", PAIRS, List.of("Fiona Lowe"));
        assertTrue(topics.stream().anyMatch(t -> t.contains("Darcy Abbott") && t.contains("Hunter Casey") && t.contains("can't stand")),
                topics.toString());
        assertTrue(topics.stream().anyMatch(t -> t.contains("Fiona Lowe") && t.contains("Hope McNamara")), topics.toString());
        assertTrue(topics.stream().anyMatch(t -> t.contains("Fiona Lowe") && t.contains("unhappy")), topics.toString());
        assertTrue(topics.stream().noneMatch(t -> t.contains("Ginny Blackwood")), "the speaker gossips about themselves");
    }

    @Test
    void aQuarrelOfTheSpeakersOwnIsAboutTheOtherOne() {
        List<String> topics = Stakes.topics("Darcy Abbott", PAIRS, List.of());
        assertTrue(topics.stream().anyMatch(t -> t.contains("Hunter Casey") && t.contains("your nerves")), topics.toString());
    }

    @Test
    void nothingAtStakeNoTopics() {
        assertTrue(Stakes.topics("Darcy Abbott", List.of(new Stakes.Pair("A", "B", 5)), List.of()).isEmpty());
    }
}
