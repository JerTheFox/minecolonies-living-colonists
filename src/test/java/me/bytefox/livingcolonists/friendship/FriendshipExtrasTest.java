package me.bytefox.livingcolonists.friendship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Letters, the work bonus and help in trouble (#67): the tables and the rules, without a colony. */
class FriendshipExtrasTest {
    @BeforeAll
    static void tables() throws IOException {
        FriendshipLevels.use(read("friendship.json"));
        LetterTable.use(read("letters.json"));
        HelpTable.use(read("help.json"));
    }

    private static String read(String name) throws IOException {
        try (InputStream in = FriendshipExtrasTest.class.getResourceAsStream("/defaults/content/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theWorkBonusGrowsWithFriendshipAndFallsOnlyWithRealEnmity() {
        assertEquals(0, WorkBonus.fraction(10), 1e-9, "a mild liking counts");
        assertEquals(0.25, WorkBonus.fraction(25), 1e-9);
        assertEquals(1, WorkBonus.fraction(90), 1e-9);
        assertEquals(0, WorkBonus.fraction(-15), 1e-9, "coolness already slows them down");
        assertEquals(-0.5, WorkBonus.fraction(-25), 1e-9);
        assertEquals(-1, WorkBonus.fraction(-60), 1e-9);
    }

    @Test
    void lettersComeFromFriendsOnlyAndMoreOftenFromCloseOnes() {
        assertEquals(0, LetterTable.chance("likes"), 1e-9);
        assertTrue(LetterTable.chance("best") > LetterTable.chance("friend"));
        assertEquals("friend", LetterTable.kind("good"));
        assertEquals("close", LetterTable.kind("close"));
        assertEquals("best", LetterTable.kind("best"));
        assertEquals(null, LetterTable.kind("likes"));
    }

    @Test
    void aGiftFromAJobOrFromEveryonesList() {
        ItemStack baker = LetterTable.gift("baker", new Random(1));
        assertTrue(List.of(Items.BREAD, Items.COOKIE, Items.CAKE).contains(baker.getItem()), baker.toString());
        assertFalse(LetterTable.gift("nobody_has_this_job", new Random(1)).isEmpty());
        assertFalse(LetterTable.keepsake(new Random(1)).isEmpty());
    }

    @Test
    void helpByJob() {
        assertEquals("heal", HelpTable.action("healer").kind());
        assertEquals("fight", HelpTable.action("knight").kind());
        assertEquals("call", HelpTable.action("teacher").kind());
        ItemStack potion = HelpTable.action("alchemist").items(new Random(1)).get(0);
        assertEquals(Items.POTION, potion.getItem());
        assertTrue(potion.has(DataComponents.POTION_CONTENTS), "a potion with nothing in it");
        assertTrue(HelpTable.rank("fight") < HelpTable.rank("call"), "a guard comes before a call for help");
    }
}
