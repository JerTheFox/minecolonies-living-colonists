package me.bytefox.livingcolonists.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Recent lines of one conversation, oldest first, trimmed by count and age. Server thread only. */
public final class Transcript {
    private final Deque<MemoryLine> lines = new ArrayDeque<>();

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

    private void trim(int maxLines, long now, long maxAgeTicks) {
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && now - lines.peekFirst().gameTime() > maxAgeTicks) {
            lines.removeFirst();
        }
    }
}
