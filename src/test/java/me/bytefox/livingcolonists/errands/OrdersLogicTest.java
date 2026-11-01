package me.bytefox.livingcolonists.errands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrdersLogicTest {
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID BEA = UUID.randomUUID();

    @Test
    void shortAnswersConfirmOrCancel() {
        assertEquals(OrderBook.Answer.YES, OrderBook.answer("да"));
        assertEquals(OrderBook.Answer.YES, OrderBook.answer("Да, давай!"));
        assertEquals(OrderBook.Answer.YES, OrderBook.answer("yes please"));
        assertEquals(OrderBook.Answer.NO, OrderBook.answer("нет"));
        assertEquals(OrderBook.Answer.NO, OrderBook.answer("не надо"));
        assertEquals(OrderBook.Answer.NO, OrderBook.answer("cancel"));
        assertEquals(OrderBook.Answer.NONE, OrderBook.answer("не знаю"));
        assertEquals(OrderBook.Answer.NONE, OrderBook.answer("do you know where the bakery is"));
        assertEquals(OrderBook.Answer.NONE, OrderBook.answer("да, но сначала построй склад и потом пекарню"));
    }

    @Test
    void eachPlayerAnswersOnlyTheirOwnOrder() {
        OrderBook<String> book = new OrderBook<>();
        book.propose(ALEX, "upgrade bakery", 0);
        assertEquals(Optional.empty(), book.take(BEA, 10));
        assertEquals(Optional.of("upgrade bakery"), book.take(ALEX, 10));
        assertEquals(Optional.empty(), book.take(ALEX, 11));
    }

    @Test
    void anUnansweredOrderExpires() {
        OrderBook<String> book = new OrderBook<>();
        book.propose(ALEX, "upgrade bakery", 0);
        assertTrue(book.pending(ALEX, OrderBook.WAIT_TICKS).isPresent());
        assertFalse(book.pending(ALEX, OrderBook.WAIT_TICKS + 1).isPresent());
    }

    @Test
    void theLastOrderCanBeUndoneOnce() {
        OrderBook<String> book = new OrderBook<>();
        int[] undone = {0};
        book.done(ALEX, "first", () -> undone[0] = 1);
        book.done(ALEX, "second", () -> undone[0] = 2);
        book.done(ALEX, "no undo", null);
        book.takeUndo(ALEX).orElseThrow().undo().run();
        assertEquals(2, undone[0]);
        assertTrue(book.takeUndo(ALEX).isEmpty());
    }

    @Test
    void ordersMustComeFromThePlayersWords() {
        List<String> said = List.of("привет", "Идрис, улучши пекарню до третьего уровня");
        assertTrue(PlayerWords.grounded("улучши пекарню", said));
        assertTrue(PlayerWords.grounded("пекарню", said));
        assertFalse(PlayerWords.grounded("снеси склад", said));
        assertFalse(PlayerWords.grounded("", said));
        assertTrue(PlayerWords.grounded("craft 10 oak planks", List.of("can you craft some oak planks?")));
    }
}
