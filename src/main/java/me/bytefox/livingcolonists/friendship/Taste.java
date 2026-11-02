package me.bytefox.livingcolonists.friendship;

import me.bytefox.livingcolonists.bonds.Bonds;
import me.bytefox.livingcolonists.bonds.Gifts;

/** What a colonist thinks of a gift (#67), and how much it moves their rapport with the giver. */
public enum Taste {
    LOVE, LIKE, NEUTRAL, DISLIKE, HATE;

    /** The change in rapport for a gift of this worth (1-5): a loved one counts three times, a hated one like rudeness. */
    public int warmth(int worth) {
        int base = Gifts.warmth(worth);
        return switch (this) {
            case LOVE -> base * 3;
            case LIKE -> (int) Math.round(base * 1.5);
            case NEUTRAL -> base;
            case DISLIKE -> -Math.max(2, base);
            case HATE -> Bonds.RUDE;
        };
    }
}
