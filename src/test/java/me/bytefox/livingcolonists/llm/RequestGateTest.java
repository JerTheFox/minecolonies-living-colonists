package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class RequestGateTest {
    @Test
    void playersKeepASlotBackgroundCanNotTake() {
        RequestGate gate = new RequestGate(2, 2);
        assertTrue(gate.acquire(true).isDone());
        CompletableFuture<Void> second = gate.acquire(true);
        assertFalse(second.isDone(), "background may use one slot of two");
        assertTrue(gate.acquire(false).isDone(), "a player's request takes the kept slot");
    }

    @Test
    void aFreedSlotGoesToAPlayerFirst() {
        RequestGate gate = new RequestGate(1, 3);
        assertTrue(gate.acquire(false).isDone());
        CompletableFuture<Void> background = gate.acquire(true);
        CompletableFuture<Void> player = gate.acquire(false);
        gate.release(false);
        assertTrue(player.isDone());
        assertFalse(background.isDone());
        gate.release(false);
        assertTrue(background.isDone());
    }

    @Test
    void tooMuchWaitingBackgroundIsShed() {
        RequestGate gate = new RequestGate(2, 1);
        gate.acquire(true);
        assertTrue(gate.acquire(true) != null);
        assertNull(gate.acquire(true), "the second waiting background request is shed");
    }

    @Test
    void theBreakerPausesBackgroundAfterFailuresInARow() {
        long[] now = {0};
        Breaker breaker = new Breaker(() -> now[0]);
        for (int i = 0; i < Breaker.FAILURES; i++) {
            assertFalse(breaker.open("api"));
            breaker.failed("api");
        }
        assertTrue(breaker.open("api"));
        assertFalse(breaker.open("other"));
        now[0] += Breaker.PAUSE_MILLIS + 1;
        assertFalse(breaker.open("api"));
        breaker.failed("api");
        breaker.succeeded("api");
        breaker.failed("api");
        assertFalse(breaker.open("api"));
    }
}
