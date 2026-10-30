package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RepeatsTest {
    private static final List<String> NAMES = List.of("Эфраим Макбрайд", "Дарси Абботт", "Максим");

    @Test
    void aLineSayingAgainWhatWasSaidIsCaught() {
        List<String> recent = List.of("Ровный участок у ратуши до сих пор ничей.", "Праздник бы устроить.");
        assertTrue(Repeats.problem("А участок у ратуши так и ничей, ровный.", recent, NAMES, false).isPresent());
        assertFalse(Repeats.problem("Хлеб сегодня подгорел, повар опять отвлёкся.", recent, NAMES, false).isPresent());
    }

    @Test
    void aMotifGoingRoundIsCaughtOnlyWhenAsked() {
        List<String> recent = List.of(
                "У меня колено с утра ноет.",
                "Колено опять ноет, к дождю, наверное.",
                "Эфраим, как колено?",
                "Праздник бы устроить, с музыкой.");
        String line = "У бабушки тоже колено ныло перед дождём.";
        assertTrue(Repeats.problem(line, recent, NAMES, true).orElseThrow().contains("колено"));
        assertFalse(Repeats.problem(line, recent, NAMES, false).isPresent());
    }

    @Test
    void namesAndCommonWordsDontMakeAMotif() {
        List<String> recent = List.of("Максим сегодня строит.", "Максим сегодня копает.", "Максим сегодня рубит.");
        assertFalse(Repeats.problem("Максим сегодня рыбачит у озера.", recent, NAMES, true).isPresent());
    }
}
