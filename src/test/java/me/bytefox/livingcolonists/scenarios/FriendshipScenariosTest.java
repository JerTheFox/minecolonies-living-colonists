package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.friendship.GiftTastes;
import me.bytefox.livingcolonists.friendship.Taste;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Friendship levels with players (#67): milestones said once, and friendships that cool without contact. */
class FriendshipScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.dice(0);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    /** A big kind act: a loved gift that counts {@code times} times. */
    private void warm(int times) {
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me a cake", 3, List.of("Cake"), Taste.LOVE, Map.of(), times);
    }

    private int rapport() {
        return stage.bonds.playerRapport(darcy, stage.player.getUUID());
    }

    private long said(String words) {
        return stage.replies().stream().filter(r -> r.toString().contains(words)).count();
    }

    @Test
    void becomingFriendsIsSaidOnce() throws Exception {
        warm(2);
        assertTrue(rapport() >= 20, "not a friend yet: " + rapport());
        assertTrue(stage.runUntil(() -> said("think of them as a friend") > 0, WAIT), "the milestone wasn't said");
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me rotten flesh", 1, List.of("Rotten Flesh"), Taste.HATE,
                Map.of(), 1);
        warm(1);
        stage.runTicks(200);
        assertEquals(1, said("think of them as a friend"), "the milestone came again");
    }

    @Test
    void aGoodFriendLetsSlipTheirFavourite() throws Exception {
        warm(4);
        assertTrue(rapport() >= 40, "not a good friend yet: " + rapport());
        String favourite = BuiltInRegistries.ITEM.getKey(GiftTastes.oddLove(darcy.getColony().getID() + ":" + darcy.getId()).getItem())
                .toString();
        assertTrue(stage.runUntil(() -> said("soft spot for") > 0, WAIT), "the favourite wasn't told");
        assertTrue(stage.bonds.knownTastes(darcy, stage.player.getUUID()).getOrDefault(Taste.LOVE, List.of()).contains(favourite),
                "the notebook won't show the favourite");
    }

    @Test
    void aFriendshipCoolsWithoutContactButAFriendStaysAFriend() {
        warm(4);
        int warm = rapport();
        stage.talk.calendar().setDay(stage.colony, 0);
        stage.talk.calendar().setDay(stage.colony, 3);
        assertEquals(warm, rapport(), "cooled within the grace days");
        stage.talk.calendar().setDay(stage.colony, 4);
        assertEquals(warm - 1, rapport());
        for (int day = 5; day < 200; day++) {
            stage.talk.calendar().setDay(stage.colony, day);
        }
        assertEquals(20, rapport(), "a friend stopped being one");
    }

    @Test
    void contactKeepsAFriendshipWarm() {
        stage.talk.calendar().setDay(stage.colony, 0);
        warm(4);
        int warm = rapport();
        stage.talk.calendar().setDay(stage.colony, 3);
        warm(1);
        int warmer = rapport();
        stage.talk.calendar().setDay(stage.colony, 6);
        assertTrue(warmer > warm);
        assertEquals(warmer, rapport(), "cooled although they had just met");
    }
}
