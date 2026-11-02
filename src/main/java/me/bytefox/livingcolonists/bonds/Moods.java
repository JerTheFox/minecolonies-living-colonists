package me.bytefox.livingcolonists.bonds;

import java.util.Optional;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import me.bytefox.livingcolonists.ModLog;

/**
 * Happiness from relations (#61), through MineColonies' own happiness modifiers: how players treat a colonist (kind or
 * rude talk, gifts, promises kept or broken) and whether they live and work beside friends or people they are at odds
 * with. Each is one modifier of MineColonies' own kind, with a small weight, that runs out by itself after a few days,
 * so talk never outweighs food, housing and safety, and nothing stays behind if the mod is removed.
 * <p>
 * MineColonies averages its factors by weight: 1 is content and leaves happiness as it is, below 1 drags it down,
 * above 1 lifts it (the total stops at 10).
 */
public final class Moods {
    /** How players treat the colonist. */
    public static final String PLAYERS = "livingcolonists_players";
    /** Friends and quarrels at home and at work. */
    public static final String NEIGHBOURS = "livingcolonists_neighbours";
    /** A birthday (#67), for the day. */
    public static final String BIRTHDAY = "livingcolonists_birthday";
    static final double BIRTHDAY_FACTOR = 1.2;
    public static final double MAX = 1.3;
    public static final double MIN = 0.6;
    /** Against MineColonies' own weights: security 4, food 3, housing 3, social 2. */
    static final double WEIGHT = 1.0;
    static final int PLAYERS_DAYS = 3;
    /** Redone every game day with the relations; two days so it never lapses between two drifts. */
    static final int NEIGHBOURS_DAYS = 2;

    private Moods() {
    }

    /** The factor after a change of {@code delta} in how a player treats the colonist (see {@link Bonds#KIND}). */
    public static double treatment(double now, int delta) {
        return Math.max(MIN, Math.min(MAX, now + delta / 40.0));
    }

    /** The factor from friends and people they are at odds with among housemates and coworkers; empty for neither. */
    public static Optional<Double> neighbours(int friends, int foes) {
        if (friends == 0 && foes == 0) {
            return Optional.empty();
        }
        return Optional.of(Math.max(MIN, Math.min(MAX, 1 + 0.1 * friends - 0.15 * foes)));
    }

    /** Today's friends and quarrels beside the colonist; nothing when there are none. */
    public static void neighbours(ICitizenData citizen, int friends, int foes) {
        neighbours(friends, foes).ifPresent(factor -> {
            try {
                set(citizen, NEIGHBOURS, factor, NEIGHBOURS_DAYS);
            } catch (RuntimeException | LinkageError e) {
                ModLog.warn("Couldn't change {}'s happiness", citizen.getName(), e);
            }
        });
    }

    /** A colonist's birthday lifts them for the day (#67). */
    public static void birthday(ICitizenData citizen) {
        try {
            set(citizen, BIRTHDAY, BIRTHDAY_FACTOR, 1);
        } catch (RuntimeException | LinkageError e) {
            ModLog.warn("Couldn't change {}'s happiness", citizen.getName(), e);
        }
    }

    /**
     * How players treat a colonist, as it stands: MineColonies keeps only its own modifiers when the world loads, so the
     * mod keeps this itself and puts the modifier back (see {@link BondsService#tick}).
     *
     * @param until game time it runs out
     */
    public record Lasting(double factor, long until) {
        /** Whole game days left, at least one. */
        int days(long now) {
            return (int) Math.max(1, (until - now + 23999) / 24000);
        }
    }

    /** Puts the players' modifier on the colonist for what is left of {@code mood}. */
    static void apply(ICitizenData citizen, Lasting mood, long now) {
        try {
            set(citizen, PLAYERS, mood.factor(), mood.days(now));
        } catch (RuntimeException | LinkageError e) {
            ModLog.warn("Couldn't change {}'s happiness", citizen.getName(), e);
        }
    }

    /** Whether the colonist still has the players' modifier in MineColonies. */
    static boolean hasPlayersMood(ICitizenData citizen) {
        try {
            return citizen.getCitizenHappinessHandler().getModifier(PLAYERS) != null;
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static void set(ICitizenData citizen, String id, double factor, int days) {
        citizen.getCitizenHappinessHandler().addModifier(
                new ExpirationBasedHappinessModifier(id, WEIGHT, new StaticHappinessSupplier(factor), days));
        citizen.markDirty(0);
    }
}
