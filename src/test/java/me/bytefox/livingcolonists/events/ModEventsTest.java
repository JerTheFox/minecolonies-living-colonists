package me.bytefox.livingcolonists.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ModEventsTest {
    private record Ping(String what) {
    }

    private record Other() {
    }

    @Test
    void eachHandlerGetsItsOwnKindInOrder() {
        ModEvents bus = new ModEvents((kind, e) -> { });
        List<String> got = new ArrayList<>();
        bus.on(Ping.class, ping -> got.add("a:" + ping.what()));
        bus.on(Ping.class, ping -> got.add("b:" + ping.what()));
        bus.on(Other.class, other -> got.add("other"));
        bus.post(new Ping("x"));
        assertEquals(List.of("a:x", "b:x"), got);
    }

    @Test
    void aFailingHandlerDoesNotStopTheOthers() {
        ModEvents bus = new ModEvents((kind, e) -> { });
        List<String> got = new ArrayList<>();
        bus.on(Ping.class, ping -> {
            throw new IllegalStateException("broken");
        });
        bus.on(Ping.class, ping -> got.add(ping.what()));
        bus.post(new Ping("y"));
        assertEquals(List.of("y"), got);
    }
}
