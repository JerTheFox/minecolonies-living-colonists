package me.bytefox.livingcolonists.friendship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.bytefox.livingcolonists.prompt.PromptLibrary;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a colonist thinks of a gift (#67): their job, everyone's tastes, their character, one odd love of their own. */
class GiftTastesTest {
    @BeforeEach
    void table() {
        GiftTastes.use(PromptLibrary.bundled("content/gifts.json"));
    }

    private static Taste taste(String job, String archetype, ItemStack stack) {
        return GiftTastes.taste(job, archetype, "1:5", stack);
    }

    @Test
    void theJobComesFirst() {
        assertEquals(Taste.LOVE, taste("blacksmith", "workhorse", new ItemStack(Items.IRON_INGOT)));
        assertEquals(Taste.LOVE, taste("researcher", "thinker", new ItemStack(Items.BOOK)));
        assertEquals(Taste.LOVE, taste("cook", "workhorse", new ItemStack(Items.SUGAR)));
    }

    @Test
    void everyoneLikesFoodAndCakeAndHatesRottenFlesh() {
        assertEquals(Taste.LIKE, taste("builder", "workhorse", new ItemStack(Items.BREAD)));
        assertEquals(Taste.LOVE, taste("builder", "workhorse", new ItemStack(Items.CAKE)));
        assertEquals(Taste.HATE, taste("builder", "workhorse", new ItemStack(Items.ROTTEN_FLESH)));
        assertEquals(Taste.DISLIKE, taste("builder", "workhorse", new ItemStack(Items.DIRT)));
        assertEquals(Taste.NEUTRAL, taste("builder", "workhorse", new ItemStack(Items.REDSTONE_TORCH)));
    }

    @Test
    void characterBendsTheTaste() {
        assertEquals(Taste.NEUTRAL, taste("builder", "grumbler", new ItemStack(Items.BREAD)), "a grumbler isn't pleased by bread");
        assertEquals(Taste.NEUTRAL, taste("builder", "optimist", new ItemStack(Items.DIRT)), "an optimist takes dirt in stride");
        assertEquals(Taste.HATE, taste("builder", "optimist", new ItemStack(Items.ROTTEN_FLESH)), "nobody likes rotten flesh");
    }

    @Test
    void everyColonistHasAnOddLoveOfTheirOwn() {
        ItemStack odd = GiftTastes.oddLove("1:5");
        assertEquals(Taste.LOVE, GiftTastes.taste("builder", "grumbler", "1:5", odd));
        assertNotEquals(GiftTastes.oddLove("1:5").getItem(), GiftTastes.oddLove("1:6").getItem(), "everyone loves the same thing");
    }

    @Test
    void aMixedGiftCountsByItsBestThenItsWorst() {
        assertEquals(Taste.LOVE, GiftTastes.gift("builder", "workhorse", "1:5",
                List.of(new ItemStack(Items.CAKE), new ItemStack(Items.ROTTEN_FLESH))));
        assertEquals(Taste.HATE, GiftTastes.gift("builder", "workhorse", "1:5",
                List.of(new ItemStack(Items.REDSTONE_TORCH), new ItemStack(Items.ROTTEN_FLESH))));
    }

    @Test
    void howMuchEachTasteMoves() {
        assertTrue(Taste.LOVE.warmth(2) > Taste.LIKE.warmth(2));
        assertTrue(Taste.LIKE.warmth(2) > Taste.NEUTRAL.warmth(2));
        assertTrue(Taste.NEUTRAL.warmth(2) > 0);
        assertTrue(Taste.DISLIKE.warmth(2) < 0 && Taste.HATE.warmth(2) < Taste.DISLIKE.warmth(2));
    }
}
