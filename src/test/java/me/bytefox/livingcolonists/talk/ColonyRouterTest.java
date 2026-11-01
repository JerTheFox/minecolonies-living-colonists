package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.bytefox.livingcolonists.llm.ChatMessage;
import org.junit.jupiter.api.Test;

class ColonyRouterTest {
    private static final List<ColonyRouter.Member> ROSTER = List.of(
            new ColonyRouter.Member("Idris Y. Crawley", "builder"),
            new ColonyRouter.Member("Adair D. Asplin", "unemployed"),
            new ColonyRouter.Member("Ann Lee", "builder"),
            new ColonyRouter.Member("Tom Hart", "farmer"));

    @Test
    void promptListsTheRosterAndTheMessage() {
        List<ChatMessage> messages = ColonyRouter.prompt("Colonists:\n{roster}\nAnswer.", ROSTER, "строители, когда закончите?");

        assertTrue(messages.get(0).content().contains("3. Ann Lee — builder"), messages.get(0).content());
        assertEquals("строители, когда закончите?", messages.get(1).content());
    }

    @Test
    void parsesSelectedColonists() {
        assertEquals(List.of(0, 2), ColonyRouter.parse("{\"to\": [1, 3]}", ROSTER.size()));
    }

    @Test
    void parsesJsonWrappedInTextOrCodeFences() {
        assertEquals(List.of(3), ColonyRouter.parse("```json\n{\"to\":[4]}\n```", ROSTER.size()));
    }

    @Test
    void everyoneAndGarbageMeanNoOneInParticular() {
        assertTrue(ColonyRouter.parse("{\"to\": \"everyone\"}", ROSTER.size()).isEmpty());
        assertTrue(ColonyRouter.parse("I think the builders", ROSTER.size()).isEmpty());
        assertTrue(ColonyRouter.parse("{\"to\": [0, 9]}", ROSTER.size()).isEmpty());
    }

}
