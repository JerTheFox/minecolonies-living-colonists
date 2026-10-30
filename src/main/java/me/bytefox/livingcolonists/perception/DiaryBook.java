package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.List;

/**
 * One colonist's day as code saw it: what they did, in order, with the time of day. The same doing
 * twice in a row is one entry; only the latest few of the last game day are kept. Free of game
 * classes for unit tests.
 */
public final class DiaryBook {
    private static final long DAY = 24000;
    static final int MAX_ENTRIES = 6;

    /** @param dayTime the time of day it began, 0 to 23999 (0 is six in the morning) */
    public record Entry(long gameTime, long dayTime, String doing) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Notes what the colonist is doing now; returns whether it is something new. */
    public boolean note(long gameTime, long dayTime, String doing) {
        entries.removeIf(entry -> gameTime - entry.gameTime() > DAY);
        if (!entries.isEmpty() && entries.get(entries.size() - 1).doing().equals(doing)) {
            return false;
        }
        entries.add(new Entry(gameTime, dayTime % DAY, doing));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        return true;
    }

    /** The newest entries of the last game day, oldest first. */
    public List<Entry> latest(long gameTime, int count) {
        List<Entry> fresh = entries.stream().filter(entry -> gameTime - entry.gameTime() <= DAY).toList();
        return fresh.subList(Math.max(0, fresh.size() - count), fresh.size());
    }

    /** "In the morning: worked at the bakery; at night: slept outside". */
    public static String text(List<Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (Entry entry : entries) {
            parts.add(partOfDay(entry.dayTime()) + ": " + entry.doing());
        }
        return String.join("; ", parts);
    }

    /** Minecraft's day starts at six in the morning. */
    static String partOfDay(long dayTime) {
        long t = dayTime % DAY;
        if (t < 6000) {
            return "in the morning";
        }
        if (t < 11000) {
            return "in the afternoon";
        }
        if (t < 13000) {
            return "in the evening";
        }
        if (t < 23000) {
            return "at night";
        }
        return "at dawn";
    }
}
