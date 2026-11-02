package me.bytefox.livingcolonists.friendship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Friendship levels with players (#67): one scale for the notebook, the model and milestones, and how it cools. */
class FriendshipLevelsTest {
    @BeforeAll
    static void table() throws IOException {
        try (InputStream in = FriendshipLevelsTest.class.getResourceAsStream("/defaults/content/friendship.json")) {
            FriendshipLevels.use(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void levelsByRapport() {
        assertEquals("neutral", FriendshipLevels.level(0).id());
        assertEquals("likes", FriendshipLevels.level(5).id());
        assertEquals("friend", FriendshipLevels.level(20).id());
        assertEquals("good", FriendshipLevels.level(59).id());
        assertEquals("best", FriendshipLevels.level(100).id());
        assertEquals("odds", FriendshipLevels.level(-20).id());
        assertEquals("enemy", FriendshipLevels.level(-100).id());
        assertTrue(FriendshipLevels.level(80).rank() > FriendshipLevels.level(60).rank());
    }

    @Test
    void milestonesForTheWarmLevelsOnly() {
        assertTrue(FriendshipLevels.milestone("friend").isPresent());
        assertTrue(FriendshipLevels.milestone("good").orElseThrow().contains("{favourite}"));
        assertTrue(FriendshipLevels.milestone("likes").isEmpty());
        assertTrue(FriendshipLevels.milestone("odds").isEmpty());
    }

    @Test
    void aFriendshipCoolsAfterAFewDaysWithoutContactButAFriendStaysAFriend() {
        assertEquals(70, FriendshipLevels.cooled(70, 3), "cooled within the grace days");
        assertEquals(69, FriendshipLevels.cooled(70, 4));
        assertEquals(20, FriendshipLevels.cooled(20, 30), "a friend stopped being one");
        assertEquals(10, FriendshipLevels.cooled(10, 30), "a mild liking cooled");
    }

    @Test
    void aGrudgeFadesButNotAway() {
        assertEquals(-49, FriendshipLevels.cooled(-50, 4));
        assertEquals(-20, FriendshipLevels.cooled(-20, 30));
        assertEquals(-10, FriendshipLevels.cooled(-10, 30));
    }
}
