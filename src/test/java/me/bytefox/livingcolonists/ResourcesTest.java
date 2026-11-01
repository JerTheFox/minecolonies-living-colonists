package me.bytefox.livingcolonists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** What the jar must carry besides code: both translations complete, and the promise notebook's files. */
class ResourcesTest {
    private static JsonObject json(String path) throws IOException {
        try (InputStream in = ResourcesTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(in, "missing " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void bothLanguagesHaveTheSameStrings() throws IOException {
        Set<String> english = new TreeSet<>(json("assets/livingcolonists/lang/en_us.json").keySet());
        Set<String> russian = new TreeSet<>(json("assets/livingcolonists/lang/ru_ru.json").keySet());
        Set<String> onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(russian);
        Set<String> onlyRussian = new TreeSet<>(russian);
        onlyRussian.removeAll(english);
        assertTrue(onlyEnglish.isEmpty() && onlyRussian.isEmpty(), "only in English: " + onlyEnglish + ", only in Russian: " + onlyRussian);
    }

    @Test
    void theNotebookHasItsNameModelTextureAndRecipe() throws IOException {
        assertTrue(json("assets/livingcolonists/lang/en_us.json").has("item.livingcolonists.promise_book"));
        assertEquals("livingcolonists:item/promise_book",
                json("assets/livingcolonists/models/item/promise_book.json").getAsJsonObject("textures").get("layer0").getAsString());
        try (InputStream texture = ResourcesTest.class.getResourceAsStream("/assets/livingcolonists/textures/item/promise_book.png")) {
            assertNotNull(texture, "no texture");
        }
        JsonObject recipe = json("data/livingcolonists/recipe/promise_book.json");
        assertEquals("livingcolonists:promise_book", recipe.getAsJsonObject("result").get("id").getAsString());
        assertEquals(3, recipe.getAsJsonArray("ingredients").size());
    }
}
