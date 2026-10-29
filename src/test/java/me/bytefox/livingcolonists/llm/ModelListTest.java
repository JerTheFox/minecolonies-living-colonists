package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelListTest {
    @Test
    void readsModelIdsSorted() {
        ModelList list = ModelList.parse(200, """
                {"object":"list","data":[{"id":"deepseek-pro","object":"model"},{"id":"deepseek-flash","object":"model"}]}
                """);
        assertEquals(List.of("deepseek-flash", "deepseek-pro"), list.models());
        assertEquals(null, list.error());
    }

    @Test
    void errorsAreNamed() {
        assertTrue(ModelList.parse(401, "{\"error\":{\"message\":\"bad key\"}}").error().contains("401"));
        assertTrue(ModelList.parse(200, "not json").error().contains("malformed"));
    }
}
