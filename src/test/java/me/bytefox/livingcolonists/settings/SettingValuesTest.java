package me.bytefox.livingcolonists.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SettingValuesTest {
    private static Setting.Kind kind(Setting.Type type) {
        return new Setting.Kind(type, 1, 300, List.of("A", "B"));
    }

    @Test
    void numbersAreCheckedAgainstTheirRange() {
        assertEquals("30", SettingValues.check(kind(Setting.Type.INT), " 30 ").value());
        assertTrue(SettingValues.check(kind(Setting.Type.INT), "0").error().contains("1"));
        assertTrue(SettingValues.check(kind(Setting.Type.INT), "abc").isError());
        assertEquals("1.5", SettingValues.check(new Setting.Kind(Setting.Type.DOUBLE, 0, 2, List.of()), "1,5").value());
        assertEquals("1000000", SettingValues.check(new Setting.Kind(Setting.Type.LONG, 0, Long.MAX_VALUE, List.of()), "1000000").value());
    }

    @Test
    void choicesTextJsonAndListsAreChecked() {
        assertEquals("B", SettingValues.check(kind(Setting.Type.CHOICE), "B").value());
        assertTrue(SettingValues.check(kind(Setting.Type.CHOICE), "C").isError());
        assertEquals("true", SettingValues.check(kind(Setting.Type.BOOL), "TRUE").value());
        assertTrue(SettingValues.check(kind(Setting.Type.BOOL), "maybe").isError());
        assertEquals("{\"a\":1}", SettingValues.check(kind(Setting.Type.JSON), "{\"a\":1}").value());
        assertTrue(SettingValues.check(kind(Setting.Type.JSON), "[1]").isError());
        assertEquals("Dev=Максим,Макс\nSteve=Стив", SettingValues.check(kind(Setting.Type.LIST), " Dev=Максим,Макс \n\n Steve=Стив ").value());
    }

    @Test
    void urlsMustBeHttp() {
        Setting.Kind url = new Setting.Kind(Setting.Type.URL, 0, 0, List.of());
        assertEquals("https://api.deepseek.com", SettingValues.check(url, "https://api.deepseek.com/").value());
        assertTrue(SettingValues.check(url, "ftp://x").isError());
        assertTrue(SettingValues.check(url, "not a url").isError());
    }
}
