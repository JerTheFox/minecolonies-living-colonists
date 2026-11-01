package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.google.gson.JsonObject;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What colonists see going on around them: enemies (#62) and players fighting in the colony (#50). */
class ColonySightsScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        stage.body(stage.citizens.get(0), new BlockPos(10, 64, 10));
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private boolean replyAbout(String text) {
        return stage.replies().stream().anyMatch(r -> r.toString().contains(text));
    }

    @Test
    void aGroupOfMobsNearAColonistIsUrgentNewsInTheColonyChat() throws Exception {
        stage.enemiesAround(List.of(stage.mob(Zombie.class, 101, new BlockPos(14, 64, 10), false),
                stage.mob(Zombie.class, 102, new BlockPos(15, 64, 11), false)));
        assertTrue(stage.runUntil(() -> replyAbout("Enemies in the colony right now: 2 Zombie"), WAIT),
                "colonists said nothing about the zombies");
        assertTrue(replyAbout("Darcy Abbott sees them"), "not the colonist who sees them");
    }

    @Test
    void aLoneMobIsNoNewsUnlessItAttacks() throws Exception {
        stage.enemiesAround(List.of(stage.mob(Zombie.class, 101, new BlockPos(14, 64, 10), false)));
        stage.runUntil(() -> false, Duration.ofSeconds(3));
        assertTrue(!replyAbout("Enemies in the colony right now"), "a lone zombie was news");
    }

    @Test
    void aMobAttackingSomeoneIsNewsEvenAlone() throws Exception {
        stage.enemiesAround(List.of(stage.mob(Zombie.class, 101, new BlockPos(14, 64, 10), true)));
        assertTrue(stage.runUntil(() -> replyAbout("attacking someone"), WAIT), "an attacking zombie went unsaid");
    }

    @Test
    void askedWhereTheEnemiesAreAColonistLooksItUp() throws Exception {
        stage.enemiesAround(List.of(stage.mob(Zombie.class, 101, new BlockPos(40, 64, 0), false)));
        stage.model.callsWhenOffered("where_enemies", new JsonObject());
        stage.model.saysWhenAsked("from_you", "Один зомби, к востоку от тебя.");
        stage.playerWritesTo("Darcy Abbott", "где враги?");
        assertTrue(stage.runUntil(() -> stage.chat.toString().contains("Один зомби"), WAIT), "no answer: " + stage.chat);
        assertTrue(stage.model.requests.toString().contains("blocks east"), "the look-up didn't give the way: "
                + stage.model.requests.stream().filter(r -> r.toString().contains("from_you")).toList());
    }

    @Test
    void enemiesKilledAfterALookUpAreNotToldOfAMinuteLater() throws Exception {
        stage.enemiesAround(List.of(stage.mob(Zombie.class, 101, new BlockPos(40, 64, 0), false)));
        stage.model.callsWhenOffered("where_enemies", new JsonObject());
        stage.model.saysWhenAsked("from_you", "Один зомби, к востоку от тебя.");
        stage.playerWritesTo("Darcy Abbott", "где враги?");
        assertTrue(stage.runUntil(() -> stage.chat.toString().contains("Один зомби"), WAIT), "no answer: " + stage.chat);
        // The player kills the zombie; a minute later someone asks again.
        stage.enemiesAround(List.of());
        stage.skipTime(60 * 20);
        int before = stage.model.requests.size();
        stage.model.callsWhenOffered("where_enemies", new JsonObject());
        stage.model.saysWhenAsked("а сейчас враги есть?", "Чисто, врагов нет.");
        stage.playerWritesTo("Darcy Abbott", "а сейчас враги есть?");
        assertTrue(stage.runUntil(() -> stage.chat.toString().contains("врагов нет"), WAIT), "no answer: " + stage.chat);
        String later = stage.model.requests.stream().skip(before).map(Object::toString).reduce("", String::concat);
        assertTrue(!later.contains("blocks east"), "the dead zombie came back from a stale look-up: " + later);
    }

    @Test
    void clearingMonstersInTheColonyIsNews() throws Exception {
        for (int i = 0; i < 5; i++) {
            stage.talk.playerKilled(stage.player, stage.mob(Zombie.class, 200 + i, new BlockPos(20, 64, 20), false));
        }
        assertTrue(stage.runUntil(() -> replyAbout("has just cleared the monsters"), WAIT), "the fight went unnoticed");
    }

    @Test
    void fightingOffRaidersIsNewsSooner() throws Exception {
        for (int i = 0; i < 2; i++) {
            stage.talk.playerKilled(stage.player, stage.mob(AbstractEntityMinecoloniesRaider.class, 300 + i,
                    new BlockPos(20, 64, 20), false));
        }
        assertTrue(stage.runUntil(() -> replyAbout("is fighting off the raiders"), WAIT), "the fight with raiders went unnoticed");
    }
}
