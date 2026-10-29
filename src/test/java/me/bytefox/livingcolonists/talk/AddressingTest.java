package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

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

    @Test
    void findsEveryNamedColonistInOrder() {
        assertEquals(List.of(2, 1), Addressing.allMentioned("Ann and Adair, come here", NAMES));
    }

    @Test
    void findsRussianNamesInAnyCase() {
        List<String> names = List.of("Джон Браун", "Мария Сильверхенд", "Игорь Петров", "Анна Ли");
        assertEquals(List.of(0), Addressing.allMentioned("Передай Джону, что пора", names));
        assertEquals(List.of(1), Addressing.allMentioned("я видел Марию у склада", names));
        assertEquals(List.of(2), Addressing.allMentioned("с Игорем поговорю", names));
        assertEquals(List.of(3), Addressing.allMentioned("позови Анну", names));
        assertTrue(Addressing.allMentioned("джонатан пришёл", names).isEmpty());
    }

    @Test
    void findsLatinNamesWrittenInCyrillicAndBack() {
        assertEquals(List.of(1), Addressing.allMentioned("Адаир, посади пшеницу", NAMES));
        assertEquals(List.of(0), Addressing.allMentioned("Идрис, как склад?", NAMES));
        assertEquals(List.of(1), Addressing.allMentioned("Адаиру передай привет", NAMES));
        assertEquals(List.of(0), Addressing.allMentioned("Dzhon, hi", List.of("Джон Браун")));
        List<String> more = List.of("Rylee G. Gray", "Mary Stone", "Landyn X. Gascoigne");
        assertEquals(List.of(0), Addressing.allMentioned("так, райли, довыпендриваешься", more));
        assertEquals(List.of(2), Addressing.allMentioned("Лэндин, а ты?", more));
    }

    @Test
    void findsAUniqueSurname() {
        List<String> names = List.of("John Brown", "Mary Brown", "Idris Crawley");
        assertEquals(List.of(2), Addressing.allMentioned("Crawley, come here", names));
        assertTrue(Addressing.allMentioned("Brown, come here", names).isEmpty());
    }

    @Test
    void commonWordsAreNotNames() {
        List<String> names = List.of("Mira Stone", "Bob Hill");
        assertTrue(Addressing.allMentioned("мир большой", names).isEmpty());
        assertTrue(Addressing.allMentioned("нет, бобы не нужны", names).isEmpty());
    }

    @Test
    void findsColonistsByProfessionInEitherLanguage() {
        List<Set<String>> jobs = List.of(
                Set.of("Builder", "Строитель"),
                Set.of("Waiter", "Официант", "cook", "повар"),
                Set.of("Archer", "Лучник", "guard", "стражник"),
                Set.of("Archer Squire", "Лучник-стажёр"),
                Set.of("Nether Miner", "Шахтёр Нижнего мира"),
                Set.of("Miner", "Шахтёр"),
                Set.of());
        assertEquals(List.of(0), Addressing.byJob("Builders, what are you working on?", jobs));
        assertEquals(List.of(0), Addressing.byJob("строители, что строите?", jobs));
        assertEquals(List.of(0), Addressing.byJob("строителям нужен камень", jobs));
        assertEquals(List.of(1), Addressing.byJob("Повара, что на ужин?", jobs));
        assertEquals(List.of(2), Addressing.byJob("лучники, на стену", jobs));
        assertEquals(List.of(2), Addressing.byJob("guards!", jobs));
        assertEquals(List.of(5), Addressing.byJob("шахтёры, сколько железа?", jobs));
        assertEquals(List.of(5), Addressing.byJob("шахтеры, сколько железа?", jobs));
        assertEquals(List.of(4, 5), Addressing.byJob("шахтёр нижнего мира и шахтёр", jobs));
    }

    @Test
    void wordsLikeAProfessionAreNotAProfession() {
        List<Set<String>> jobs = List.of(Set.of("Builder", "Строитель"), Set.of("Farmer", "Фермер"));
        assertTrue(Addressing.byJob("let's build a house", jobs).isEmpty());
        assertTrue(Addressing.byJob("надо строить стену", jobs).isEmpty());
        assertTrue(Addressing.byJob("на ферме тихо", jobs).isEmpty());
    }

    @Test
    void seesAMessageForEveryone() {
        assertTrue(Addressing.toEveryone("Всем привет!"));
        assertTrue(Addressing.toEveryone("Hey everyone, raid tonight"));
        assertTrue(!Addressing.toEveryone("Adair, go plant some wheat"));
    }
}
