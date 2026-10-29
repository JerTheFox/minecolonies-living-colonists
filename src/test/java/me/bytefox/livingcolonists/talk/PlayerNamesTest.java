package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PlayerNamesTest {
    @Test
    void parsesNicknamesAndNamesMostCommonFirst() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("JerTheFox=Максим, Макс ,Максик", "Wife = Настя"));

        assertEquals(List.of("Максим", "Макс", "Максик"), names.get("jerthefox"));
        assertEquals(List.of("Настя"), names.get("wife"));
    }

    @Test
    void skipsBrokenEntries() {
        assertTrue(PlayerNames.parse(List.of("no equals sign", "=Name", "Nick=", " ")).isEmpty());
    }

    @Test
    void theFirstNameIsWhatColonistsCallThePlayer() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("JerTheFox=Максим,Макс"));

        assertEquals("Максим", PlayerNames.name("JerTheFox", names));
        assertEquals("Steve", PlayerNames.name("Steve", names));
    }

    @Test
    void theNameIsAllowedInAboutAThirdOfRepliesWithTheFirstFormLikeliest() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("JerTheFox=Максим,Макс,Максик"));
        Random random = new Random(5);
        Map<String, Integer> counts = new java.util.HashMap<>();
        int withName = 0;
        for (int i = 0; i < 3000; i++) {
            String line = PlayerNames.addressing("JerTheFox", names, random);
            if (!line.startsWith("Don't")) {
                withName++;
                for (String form : List.of("Максим", "Максик", "Макс")) {
                    if (line.contains(" " + form + " ")) {
                        counts.merge(form, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        assertTrue(withName > 750 && withName < 1050, String.valueOf(withName));
        assertTrue(counts.get("Максим") > counts.get("Макс"), counts.toString());
        assertTrue(counts.get("Макс") > counts.get("Максик"), counts.toString());
    }

    @Test
    void withoutConfiguredNamesTheNicknameIsUsed() {
        Random random = new Random(1);
        for (int i = 0; i < 50; i++) {
            String line = PlayerNames.addressing("Steve", Map.of(), random);
            assertTrue(line.startsWith("Don't") || line.contains("Steve"), line);
        }
    }
}
