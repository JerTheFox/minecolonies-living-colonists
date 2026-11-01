package me.bytefox.livingcolonists.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LifeStoriesTest {
    @Test
    void aStoryIsWrittenOnceThereIsEnoughAndThenOnceAGameWeek() {
        assertFalse(LifeStories.due(-1, 0, LifeStories.MIN_FACTS - 1), "too little to tell yet");
        assertTrue(LifeStories.due(-1, 0, LifeStories.MIN_FACTS), "the first story");
        assertFalse(LifeStories.due(1000, 1000 + LifeStories.EVERY - 1, 20), "written lately");
        assertTrue(LifeStories.due(1000, 1000 + LifeStories.EVERY, 20), "a game week later");
    }

    @Test
    void theStoryLeadsTheMemories() {
        assertTrue(LifeStories.withStory("fact", "Ann came in spring.").startsWith("Your life in the colony so far: Ann came"));
        assertTrue(LifeStories.withStory("fact", null).equals("fact"));
        assertTrue(LifeStories.withStory("", "Ann came in spring.").startsWith("Your life"));
    }
}
