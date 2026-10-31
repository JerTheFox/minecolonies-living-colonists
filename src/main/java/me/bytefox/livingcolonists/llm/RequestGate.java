package me.bytefox.livingcolonists.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * How many requests run at once (#40). A player's answer may take any free slot; background work
 * (chatter, first lines, memory, backgrounds) leaves one slot free for players, waits behind them,
 * and is shed when too much of it is waiting already. Any thread.
 */
final class RequestGate {
    private final int slots;
    private final int maxWaiting;
    private int running;
    private int runningBackground;
    private final Deque<CompletableFuture<Void>> players = new ArrayDeque<>();
    private final Deque<CompletableFuture<Void>> background = new ArrayDeque<>();

    RequestGate(int slots, int maxWaiting) {
        this.slots = Math.max(1, slots);
        this.maxWaiting = maxWaiting;
    }

    /** Completes when the request may run; null if a background request is shed. Pair with {@link #release}. */
    synchronized CompletableFuture<Void> acquire(boolean isBackground) {
        if (isBackground ? canRunBackground() : running < slots) {
            start(isBackground);
            return CompletableFuture.completedFuture(null);
        }
        if (isBackground && background.size() >= maxWaiting) {
            return null;
        }
        CompletableFuture<Void> turn = new CompletableFuture<>();
        (isBackground ? background : players).addLast(turn);
        return turn;
    }

    /** A request finished: its slot goes to a waiting player first, then to waiting background work. */
    void release(boolean wasBackground) {
        CompletableFuture<Void> next;
        synchronized (this) {
            running--;
            if (wasBackground) {
                runningBackground--;
            }
            if (!players.isEmpty()) {
                next = players.removeFirst();
                start(false);
            } else if (!background.isEmpty() && canRunBackground()) {
                next = background.removeFirst();
                start(true);
            } else {
                return;
            }
        }
        next.complete(null);
    }

    private boolean canRunBackground() {
        return running < slots && runningBackground < Math.max(1, slots - 1);
    }

    private void start(boolean isBackground) {
        running++;
        if (isBackground) {
            runningBackground++;
        }
    }
}
