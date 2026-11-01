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
        Map<String, List<String>> names = PlayerNames.parse(List.of("Steve42=Steven, Steve ,Stevie", "Alex_7 = Alexandra"));

        assertEquals(List.of("Steven", "Steve", "Stevie"), names.get("steve42"));
        assertEquals(List.of("Alexandra"), names.get("alex_7"));
    }

    @Test
    void skipsBrokenEntries() {
        assertTrue(PlayerNames.parse(List.of("no equals sign", "=Name", "Nick=", " ")).isEmpty());
    }

    @Test
    void theFirstNameIsWhatColonistsCallThePlayer() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("Steve42=Steven,Steve"));

        assertEquals("Steven", PlayerNames.name("Steve42", names));
        assertEquals("Steve", PlayerNames.name("Steve", names));
    }

    @Test
    void theNameIsAllowedInAboutAThirdOfRepliesWithTheFirstFormLikeliest() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("Steve42=Steven,Steve,Stevie"));
        Random random = new Random(5);
        Map<String, Integer> counts = new java.util.HashMap<>();
        int withName = 0;
        for (int i = 0; i < 3000; i++) {
            String line = PlayerNames.addressing("Steve42", names, random, 60);
            if (!line.startsWith("Don't")) {
                withName++;
                for (String form : List.of("Steven", "Stevie", "Steve")) {
                    if (line.contains(" " + form + " ")) {
                        counts.merge(form, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        assertTrue(withName > 750 && withName < 1050, String.valueOf(withName));
        assertTrue(counts.get("Steven") > counts.get("Steve"), counts.toString());
        assertTrue(counts.get("Steve") > counts.get("Stevie"), counts.toString());
    }

    @Test
    void withoutConfiguredNamesTheNicknameIsUsed() {
        Random random = new Random(1);
        for (int i = 0; i < 50; i++) {
            String line = PlayerNames.addressing("Steve", Map.of(), random);
            assertTrue(line.startsWith("Don't") || line.contains("Steve"), line);
        }
    }

    @Test
    void howWarmlyAColonistCallsThePlayerFollowsTheirRelation() {
        Map<String, List<String>> names = PlayerNames.parse(List.of("Steve42=Steven,Steve,Stevie"));
        Random random = new Random(9);
        for (int i = 0; i < 300; i++) {
            assertTrue(PlayerNames.addressing("Steve42", names, random, -50).startsWith("Don't use"));
            String neutral = PlayerNames.addressing("Steve42", names, random, 0);
            assertTrue(neutral.startsWith("Don't use") || neutral.contains(" Steven "), neutral);
        }
        boolean familiar = false;
        for (int i = 0; i < 300; i++) {
            familiar |= PlayerNames.addressing("Steve42", names, random, 60).contains("Stevie");
        }
        assertTrue(familiar);
    }
}
