package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The routes a colony message takes, from the cases seen on the server. */
class MessageRoutingTest {
    private static final List<String> NAMES = List.of("Рэндольф Кейси", "Хоуп Макнамара", "Дарси Абботт");
    private static final List<Set<String>> JOBS = List.of(Set.of("builder", "строитель"), Set.of(), Set.of("cook", "повар"));

    private static Optional<List<Integer>> route(String message, int justSpoke) {
        return MessageRouting.byCode(message, NAMES, JOBS, justSpoke, 3, new Random(1));
    }

    @Test
    void aNameOrAProfessionIsFoundByCode() {
        assertEquals(Optional.of(List.of(0)), route("рэндольф скрафти факел", -1));
        assertEquals(Optional.of(List.of(0)), route("строители стройте столовую", -1));
        assertEquals(Optional.of(List.of(2, 0)), route("Дарси и Рэндольф, идите сюда", -1));
    }

    @Test
    void youWithoutANameGoesToWhoeverJustSpoke() {
        assertEquals(Optional.of(List.of(1)), route("ты сначала первый этаж обживи", 1));
        assertEquals(Optional.of(List.of(1)), route("На что жалуешься?", 1));
        assertTrue(route("ты сначала первый этаж обживи", -1).isEmpty(), "nobody spoke just now: the router decides");
    }

    @Test
    void aBuildingNameDoesNotCallItsWorker() {
        assertTrue(route("кто пойдет в хижину строителя?", -1).isEmpty());
    }

    @Test
    void everyoneWithoutAQuestionNeedsNobodyInParticular() {
        assertEquals(Optional.of(List.of()), route("всем привет", -1));
        assertTrue(route("ребята, как вы там?", -1).isEmpty(), "a question to everyone goes to the router");
        assertTrue(route("безработные ко мне подойдите", -1).isEmpty(), "\"the unemployed\" is the router's to find");
    }
}
