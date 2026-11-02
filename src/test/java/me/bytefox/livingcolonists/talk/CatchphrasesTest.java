package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;

import me.bytefox.livingcolonists.prompt.PromptLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Catchphrases (#65): rare, with cooldowns, a name put in, and a set reply. */
class CatchphrasesTest {
    private static final long MINUTE = 60 * 20;
    private static final long DAY = 24000;
    private Catchphrases phrases;

    /** Every roll comes out 0: the chance always passes, the first fitting phrase is taken. */
    private static final Random ALWAYS = new Random() {
        @Override
        public double nextDouble() {
            return 0;
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }
    };

    @BeforeEach
    void table() {
        Catchphrases.use(PromptLibrary.bundled("content/catchphrases.json"));
        phrases = new Catchphrases();
    }

    private Optional<Catchphrases.Said> roll(String speaker, String friend, String enemy, long now, Random random) {
        return phrases.maybe(1, speaker, "Russian", friend, enemy, now, now, random);
    }

    @Test
    void aPhraseWithItsReplyFromTheFile() {
        Catchphrases.Said said = roll("1:5", null, null, 0, ALWAYS).orElseThrow();
        assertEquals("Опять работать? Мы же вчера работали!", said.line());
        assertEquals("И позавчера тоже.", said.reply());
    }

    @Test
    void rareByDefault() {
        Random random = new Random(3);
        int said = 0;
        for (int i = 0; i < 1000; i++) {
            Catchphrases fresh = new Catchphrases();
            if (fresh.maybe(1, "1:5", "Russian", null, null, 0, 0, random).isPresent()) {
                said++;
            }
        }
        assertTrue(said > 10 && said < 60, "about 3%, was " + said + " of 1000");
    }

    @Test
    void onePerColonyIn20MinutesOnePerColonistIn3DaysTheSamePhraseNotWithinAWeek() {
        assertTrue(roll("1:5", null, null, 0, ALWAYS).isPresent());
        assertTrue(roll("1:6", null, null, 10 * MINUTE, ALWAYS).isEmpty(), "the colony's cooldown");
        assertTrue(roll("1:5", null, null, 25 * MINUTE, ALWAYS).isEmpty(), "the colonist's cooldown");
        Catchphrases.Said next = roll("1:6", null, null, 25 * MINUTE, ALWAYS).orElseThrow();
        assertTrue(!next.line().equals("Опять работать? Мы же вчера работали!"), "the same phrase again within a week");
        assertTrue(roll("1:5", null, null, 3 * DAY + MINUTE, ALWAYS).isPresent(), "a colonist may again after 3 days");
    }

    @Test
    void aNameGoesIntoThePhraseAndThatColonistReplies() {
        Random enemyFirst = new Random() {
            @Override
            public double nextDouble() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
        Catchphrases.use("""
                {"chance": 1, "colonyCooldownMinutes": 0, "colonistCooldownDays": 0, "phraseCooldownDays": 0,
                 "replyChance": 1, "heardChance": 0,
                 "languages": {"Russian": {"pairs": [{"line": "{enemy}, если что пропадёт — я на тебя подумаю.", "reply": "Взаимно."}],
                                           "heard": []}}}
                """);
        Catchphrases.Said said = roll("1:5", null, "Хантер Кейси", 0, enemyFirst).orElseThrow();
        assertTrue(said.line().startsWith("Хантер Кейси"), said.line());
        assertEquals("Хантер Кейси", said.replier());
    }

    @Test
    void aPhraseNeedingANameNobodyFitsIsNotSaid() {
        for (int i = 0; i < 50; i++) {
            Catchphrases fresh = new Catchphrases();
            fresh.maybe(1, "1:5", "Russian", null, null, 0, 0, new Random(i))
                    .ifPresent(said -> assertTrue(!said.line().contains("{"), said.line()));
        }
    }

    @Test
    void aNodWithoutAnAnswerOfItsOwnGetsHeardThatOneOrNothing() {
        Catchphrases.Said nod = new Catchphrases.Said("Зима близко.", "", null);
        for (int i = 0; i < 50; i++) {
            Catchphrases.answer(nod, "Russian", new Random(i)).ifPresent(answer -> assertTrue(!answer.isEmpty()));
        }
        assertEquals(Optional.empty(), Catchphrases.answer(nod, "Russian", ALWAYS).filter(String::isEmpty));
    }
}
