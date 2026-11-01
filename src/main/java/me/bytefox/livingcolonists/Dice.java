package me.bytefox.livingcolonists;

import java.util.Random;

/**
 * Where the mod's "now and then" comes from: one random source for every roll of the dice (greetings, gossip, life
 * news, who speaks next). In the game it is plain randomness; a test swaps the source to make a rare event happen
 * on the spot, or to repeat a run exactly (#64).
 */
public final class Dice extends Random {
    private static volatile Random source = new Random();
    private static final Dice SHARED = new Dice();

    private Dice() {
    }

    /** The mod's random source: every call goes to whatever source is set now. */
    public static Random random() {
        return SHARED;
    }

    /** For tests: rolls come from {@code random} from now on; {@code null} puts plain randomness back. */
    public static void use(Random random) {
        source = random == null ? new Random() : random;
    }

    @Override
    protected int next(int bits) {
        return source.nextInt() >>> (32 - bits);
    }

    @Override
    public int nextInt() {
        return source.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return source.nextInt(bound);
    }

    @Override
    public long nextLong() {
        return source.nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return source.nextBoolean();
    }

    @Override
    public float nextFloat() {
        return source.nextFloat();
    }

    @Override
    public double nextDouble() {
        return source.nextDouble();
    }

    @Override
    public synchronized double nextGaussian() {
        return source.nextGaussian();
    }
}
