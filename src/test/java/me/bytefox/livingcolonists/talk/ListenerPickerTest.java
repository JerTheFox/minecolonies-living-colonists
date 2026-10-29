package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import me.bytefox.livingcolonists.talk.ListenerPicker.Candidate;
import org.junit.jupiter.api.Test;

class ListenerPickerTest {
    // Player looks along +X.
    private static Optional<String> pick(List<Candidate<String>> candidates) {
        return ListenerPicker.pick(candidates, 1, 0, 0, 8.0, 0.8);
    }

    @Test
    void prefersTheColonistUnderTheCrosshairOverACloserOne() {
        assertEquals(Optional.of("looked-at"), pick(List.of(
                new Candidate<>("closer", 0, 0, 2),
                new Candidate<>("looked-at", 6, 0.3, 0))));
    }

    @Test
    void picksTheFirstColonistAlongTheLineOfSight() {
        assertEquals(Optional.of("front"), pick(List.of(
                new Candidate<>("behind-front", 6, 0, 0),
                new Candidate<>("front", 3, 0, 0))));
    }

    @Test
    void fallsBackToTheNearestWhenNobodyIsLookedAt() {
        assertEquals(Optional.of("near"), pick(List.of(
                new Candidate<>("far", 0, 0, 7),
                new Candidate<>("near", -2, 0, 1))));
    }

    @Test
    void ignoresColonistsOutOfRange() {
        assertTrue(pick(List.of(new Candidate<>("too-far", 9, 0, 0), new Candidate<>("aside", 0, 0, 8.5))).isEmpty());
    }

    @Test
    void ignoresColonistsBehindThePlayerForTheCrosshair() {
        assertEquals(Optional.of("side"), pick(List.of(
                new Candidate<>("behind", -3, 0, 0),
                new Candidate<>("side", 0, 0, 2.5))));
    }
}
