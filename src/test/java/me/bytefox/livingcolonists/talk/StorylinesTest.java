package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.bytefox.livingcolonists.bonds.Relation;
import org.junit.jupiter.api.Test;

/** A colonist's running story (#65): the strongest tie they have, with what happened in it so far. */
class StorylinesTest {
    private static Relation relation(int rapport, String... notes) {
        Relation relation = new Relation(rapport, List.of());
        long time = 0;
        for (String note : notes) {
            relation = new Relation(relation.rapport(), concat(relation.notes(), new Relation.Note(time++, note, rapport > 0)));
        }
        return relation;
    }

    private static List<Relation.Note> concat(List<Relation.Note> notes, Relation.Note note) {
        List<Relation.Note> out = new java.util.ArrayList<>(notes);
        out.add(note);
        return out;
    }

    @Test
    void theStrongestTieWithAHistoryIsTheStory() {
        Map<String, Relation> ties = new LinkedHashMap<>();
        ties.put("Hunter Casey", relation(-45, "argued over the woodpile", "Hunter called the bread stale"));
        ties.put("Fiona Lowe", relation(30, "helped with the field"));
        ties.put("Ginny Blackwood", relation(-90));
        Storylines.Story story = Storylines.story(ties).orElseThrow();
        assertEquals("Hunter Casey", story.other(), "a tie without a history has no story to carry on");
        assertTrue(story.topic().contains("Hunter called the bread stale"), "not the latest moment: " + story.topic());
        assertTrue(story.topic().contains("can't stand"), story.topic());
    }

    @Test
    void weakTiesMakeNoStory() {
        Map<String, Relation> ties = Map.of("Hunter Casey", relation(10, "said hello"));
        assertEquals(Optional.empty(), Storylines.story(ties));
    }

    @Test
    void aFriendshipIsAStoryToo() {
        Storylines.Story story = Storylines.story(Map.of("Fiona Lowe", relation(40, "helped with the field"))).orElseThrow();
        assertTrue(story.topic().contains("close") && story.topic().contains("helped with the field"), story.topic());
    }
}
