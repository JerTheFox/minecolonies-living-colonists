package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.notebook.Notebook;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A big colony's relations in the notebook (#67): friends only until the player asks for everyone. */
class NotebookRelationsTest {
    private Stage stage;
    private Notebook notebook;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey", "Ella Moss", "Owen Price", "Ruth Hale", "Ivan Cole", "Nina Shaw",
                "Leo Grant", "Mara Quinn", "Tom Reed");
        notebook = new Notebook(stage.bonds, stage.talk.errands(), stage.talk.moods(), stage.talk.calendar(), stage.talk.letters());
        for (int i = 0; i < stage.citizens.size(); i++) {
            // The first a friend, the rest only acquainted.
            stage.bonds.gift(stage.citizens.get(i), stage.player.getUUID(), "Alex", "Alex gave me bread", 1, List.of("Bread"),
                    i == 0 ? Taste.LOVE : Taste.NEUTRAL, Map.of(), i == 0 ? 3 : 1);
            if (i == 0) {
                stage.bonds.gift(stage.citizens.get(i), stage.player.getUUID(), "Alex", "Alex gave me cake", 5, List.of("Cake"),
                        Taste.LOVE, Map.of(), 3);
            }
        }
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private String relations() {
        return notebook.sections(stage.player).stream().filter(s -> s.key().equals("relations")).findFirst().orElseThrow()
                .pages().stream().map(Component::getString).reduce("", String::concat);
    }

    @Test
    void aBigColonyShowsFriendsUntilAskedForAll() {
        String friends = relations();
        assertTrue(friends.contains("Darcy Abbott") && !friends.contains("Tom Reed"), friends);
        notebook.showAll(stage.player, true);
        String all = relations();
        assertTrue(all.contains("Darcy Abbott") && all.contains("Tom Reed"), all);
    }
}
