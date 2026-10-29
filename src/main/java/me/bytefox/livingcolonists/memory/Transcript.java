package me.bytefox.livingcolonists.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Recent lines of one conversation, oldest first, trimmed by count and age. Server thread only. */
public final class Transcript {
    private final Deque<MemoryLine> lines = new ArrayDeque<>();
    /** Game time of the last line already written down as facts (see {@link FactBook}). */
    private long digested = Long.MIN_VALUE;

    /** Adds a line, then drops what is over {@code maxLines} or older than {@code maxAgeTicks}. */
    public void add(MemoryLine line, int maxLines, long maxAgeTicks) {
        lines.addLast(line);
        trim(maxLines, line.gameTime(), maxAgeTicks);
    }

    /** The lines still remembered at {@code now}. */
    public List<MemoryLine> lines(long now, long maxAgeTicks) {
        return lines.stream().filter(line -> now - line.gameTime() <= maxAgeTicks).toList();
    }

    public List<MemoryLine> all() {
        return List.copyOf(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void clear() {
        lines.clear();
    }

    /** Lines not yet written down as facts. */
    public List<MemoryLine> undigested() {
        return lines.stream().filter(line -> line.gameTime() > digested).toList();
    }

    /** Up to {@code max} of the last lines already written down, for context. */
    public List<MemoryLine> digestedTail(int max) {
        List<MemoryLine> done = lines.stream().filter(line -> line.gameTime() <= digested).toList();
        return done.subList(Math.max(0, done.size() - max), done.size());
    }

    public long digested() {
        return digested;
    }

    /** Marks the lines up to this game time as written down; never moves back. */
    public void markDigested(long gameTime) {
        digested = Math.max(digested, gameTime);
    }

    /** Game time of the newest line, or {@link Long#MIN_VALUE} when empty. */
    public long lastTime() {
        return lines.isEmpty() ? Long.MIN_VALUE : lines.peekLast().gameTime();
    }

    private void trim(int maxLines, long now, long maxAgeTicks) {
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && now - lines.peekFirst().gameTime() > maxAgeTicks) {
            lines.removeFirst();
        }
    }
}
