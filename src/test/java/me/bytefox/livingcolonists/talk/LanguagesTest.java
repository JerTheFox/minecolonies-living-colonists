package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LanguagesTest {
    private static final List<String> NAMES = List.of("Джон Браун", "Adair D. Asplin");

    @Test
    void namesTheLanguageOfALocale() {
        assertEquals("Russian", Languages.ofLocale("ru_ru"));
        assertEquals("English", Languages.ofLocale("en_us"));
        assertEquals("German", Languages.ofLocale("de_de"));
        assertEquals("English", Languages.ofLocale(""));
    }

    @Test
    void cyrillicMessageIsAnsweredInTheCyrillicLanguageOfTheLocale() {
        assertEquals(Optional.of("Russian"), reply("Привет, как дела?", "en_us"));
        assertEquals(Optional.of("Russian"), reply("Привет, как дела?", "ru_ru"));
        assertEquals(Optional.of("Ukrainian"), reply("Привіт, як справи?", "uk_ua"));
    }

    @Test
    void englishMessageIsAnsweredInEnglishWhateverTheLocale() {
        assertEquals(Optional.of("English"), reply("How are you doing today?", "ru_ru"));
        assertEquals(Optional.of("English"), reply("Hey, what's up with the farm?", "de_de"));
        assertEquals(Optional.of("English"), reply("wheat or carrots", "en_us"));
    }

    @Test
    void namesInAnotherAlphabetDoNotCount() {
        assertEquals(Optional.of("English"), reply("Джон, how are you?", "ru_ru"));
        assertEquals(Optional.of("Russian"), reply("Adair, иди сюда", "en_us"));
    }

    @Test
    void latinMessageInAnotherLanguageIsLeftToTheModel() {
        assertEquals(Optional.empty(), reply("Wie geht es dir heute?", "de_de"));
        assertEquals(Optional.empty(), reply("privet kak dela", "ru_ru"));
    }

    @Test
    void shortMessageKeepsTheLanguageSoFarOrTheLocale() {
        assertEquals(Optional.of("English"), Languages.reply("ок", "ru_ru", NAMES, Optional.of("English")));
        assertEquals(Optional.of("Russian"), Languages.reply("ok", "ru_ru", NAMES, Optional.empty()));
        assertEquals(Optional.of("Russian"), Languages.reply("Джон?", "ru_ru", NAMES, Optional.empty()));
    }

    @Test
    void otherAlphabets() {
        assertEquals(Optional.of("Japanese"), reply("こんにちは、元気ですか", "en_us"));
        assertEquals(Optional.of("Chinese"), reply("你好，今天怎么样", "en_us"));
        assertEquals(Optional.of("Korean"), reply("안녕하세요 잘 지내요", "en_us"));
    }

    @Test
    void findsAnEnglishWordStrayedIntoARussianLine() {
        assertEquals(Optional.of("sketches"), Languages.strayEnglish("Пока только sketches в голове."));
        assertEquals(Optional.of("well"), Languages.strayEnglish("Ну, well, посмотрим, что выйдет к вечеру."));
    }

    @Test
    void namesAbbreviationsAndEnglishLinesAreLeftAlone() {
        assertEquals(Optional.empty(), Languages.strayEnglish("Adair, спроси у Dev, он знает."), "names start with a capital");
        assertEquals(Optional.empty(), Languages.strayEnglish("Поставь TNT подальше от склада, ок?"), "an abbreviation");
        assertEquals(Optional.empty(), Languages.strayEnglish("Not yet, the bakery needs more wheat."), "an English line");
        assertEquals(Optional.empty(), Languages.strayEnglish("Хлеба 64 штуки, x2 к вечеру."), "a short token");
        assertEquals(Optional.empty(), Languages.strayEnglish(""));
    }

    private static Optional<String> reply(String message, String locale) {
        return Languages.reply(message, locale, NAMES, Optional.empty());
    }
}
