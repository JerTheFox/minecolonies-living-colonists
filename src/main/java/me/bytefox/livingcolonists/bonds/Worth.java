package me.bytefox.livingcolonists.bonds;

/**
 * What a promised item is worth, 1 (a trifle) to 5 (something precious), by code: from what the
 * game itself says about the item, so it works for every mod without a hand-made table. Each step
 * of value makes one item four times dearer; the amount multiplies it.
 */
public final class Worth {
    /** What an item is, by the common tags mods put on their items. */
    public enum Kind {
        PLAIN(0), INGOT(1), ORE(1), GEM(2), STORAGE_BLOCK(3);

        final int steps;

        Kind(int steps) {
            this.steps = steps;
        }
    }

    /**
     * @param rarity     0 common, 1 uncommon, 2 rare, 3 epic, as the item's mod sets it
     * @param durability how many uses a tool or armour piece lasts, 0 if it doesn't wear out
     * @param maxStack   how many fit in one slot: 1 for tools, armour and machines
     */
    public record Traits(int rarity, Kind kind, int durability, boolean enchanted, int maxStack) {
    }

    /** What a single item of no value at all counts for, so that piles of dirt stay a trifle. */
    private static final double NOTHING = 0.002;
    private static final int[] THRESHOLDS = {2, 16, 128, 512};

    private Worth() {
    }

    public static int of(Traits item, int count) {
        int steps = 2 * item.rarity() + item.kind().steps + (item.enchanted() ? 2 : 0) + (item.maxStack() == 1 ? 1 : 0)
                + (item.durability() > 1000 ? 2 : item.durability() > 200 ? 1 : 0);
        double each = steps == 0 ? NOTHING : Math.pow(4, steps - 1);
        double total = each * Math.max(0, count);
        int worth = 1;
        for (int threshold : THRESHOLDS) {
            if (total >= threshold) {
                worth++;
            }
        }
        return worth;
    }
}
