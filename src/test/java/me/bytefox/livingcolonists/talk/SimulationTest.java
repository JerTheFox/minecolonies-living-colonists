package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

/**
 * An hour of a colony with two players, on a fake clock (#40): every subsystem asks the shared budget
 * as eagerly as it ever would, and the lines that come out must stay few, with the important ones
 * never lost. A guard against noise for later changes.
 */
class SimulationTest {
    private static final long HOUR = 60 * 60 * 20;
    private static final long STEP = 5 * 20;

    private record Wanted(Airtime.Kind kind, String bucket) {
    }

    @Test
    void anHourStaysQuietAndLosesNothingImportant() {
        Airtime airtime = new Airtime();
        Random random = new Random(42);
        String colony = Airtime.colony(1);
        String[] players = {Airtime.player(new UUID(0, 1)), Airtime.player(new UUID(0, 2))};
        Map<Airtime.Kind, Integer> said = new EnumMap<>(Airtime.Kind.class);
        Map<Airtime.Kind, Integer> wanted = new EnumMap<>(Airtime.Kind.class);
        // Notes and urgent news wait for their turn, like the event queue and the note book do.
        Deque<Wanted> waiting = new ArrayDeque<>();
        int colonyLines = 0;
        int[] privateLines = new int[players.length];
        for (long now = 0; now < HOUR; now += STEP) {
            Deque<Wanted> asks = new ArrayDeque<>(waiting);
            int retries = waiting.size();
            waiting.clear();
            // Far more eager than the real subsystems: chatter and first lines try every minute or two.
            if (now % (2 * 60 * 20) == 0) {
                asks.add(new Wanted(Airtime.Kind.CHATTER, colony));
            }
            for (String player : players) {
                if (random.nextInt(12) == 0) {
                    asks.add(new Wanted(Airtime.Kind.FIRST_LINE, player));
                }
            }
            if (now % (10 * 60 * 20) == 0) {
                asks.add(new Wanted(Airtime.Kind.NEWS, colony));
            }
            if (now == 20 * 60 * 20 || now == 21 * 60 * 20) {
                asks.add(new Wanted(Airtime.Kind.URGENT, colony));
            }
            if (now == 15 * 60 * 20 || now == 45 * 60 * 20) {
                asks.add(new Wanted(Airtime.Kind.NOTE, players[0]));
                asks.add(new Wanted(Airtime.Kind.GREETING, players[1]));
            }
            int index = 0;
            for (Wanted ask : asks) {
                if (index++ >= retries) {
                    wanted.merge(ask.kind(), 1, Integer::sum);
                }
                if (airtime.allows(ask.kind(), ask.bucket(), now, Level.DEFAULT)) {
                    airtime.spend(ask.bucket(), now);
                    said.merge(ask.kind(), 1, Integer::sum);
                    if (ask.bucket().equals(colony)) {
                        colonyLines++;
                    } else {
                        privateLines[ask.bucket().equals(players[0]) ? 0 : 1]++;
                    }
                } else if (ask.kind() == Airtime.Kind.NOTE || ask.kind() == Airtime.Kind.URGENT) {
                    waiting.add(ask);
                }
            }
        }
        long refills = HOUR / Airtime.refillTicks(Level.DEFAULT);
        assertEquals(2, said.getOrDefault(Airtime.Kind.URGENT, 0), "every death or raid is talked about");
        assertEquals(2, said.getOrDefault(Airtime.Kind.NOTE, 0), "every note gets delivered");
        assertTrue(colonyLines <= Airtime.CAPACITY + refills, "colony chat lines: " + colonyLines);
        for (int lines : privateLines) {
            assertTrue(lines <= Airtime.CAPACITY + refills, "private lines: " + lines);
        }
        assertTrue(said.getOrDefault(Airtime.Kind.CHATTER, 0) > 0, "chatter isn't starved altogether");
        assertTrue(said.getOrDefault(Airtime.Kind.CHATTER, 0) < wanted.get(Airtime.Kind.CHATTER),
                "chatter gives way: " + said + " of " + wanted);
        assertTrue(said.getOrDefault(Airtime.Kind.NEWS, 0) >= wanted.get(Airtime.Kind.NEWS) - 1,
                "news mostly goes through: " + said + " of " + wanted);
    }
}
