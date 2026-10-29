package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class AddressingTest {
    private static final List<String> NAMES = List.of("Idris Y. Crawley", "Adair D. Asplin", "Ann Lee");

    @Test
    void findsAColonistByFirstName() {
        assertEquals(OptionalInt.of(1), Addressing.mentioned("Adair, go plant some wheat", NAMES));
        assertEquals(OptionalInt.of(0), Addressing.mentioned("how is the warehouse, idris?", NAMES));
    }

    @Test
    void matchesWholeWordsOnly() {
        assertTrue(Addressing.mentioned("Annual report please", NAMES).isEmpty());
    }

    @Test
    void noNameMeansNobody() {
        assertTrue(Addressing.mentioned("Everyone, the raid is coming!", NAMES).isEmpty());
    }
}
