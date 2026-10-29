package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ChatRequestsTest {
    private static LlmSettings settings(String extraParams) {
        return new LlmSettings("https://api.example.com", "test-model", "secret-key", 30, 2000, 0.7,
                JsonParser.parseString(extraParams).getAsJsonObject());
    }

    @Test
    void buildsChatCompletionsBody() {
        JsonObject body = JsonParser.parseString(ChatRequests.toJson(settings("{}"),
                List.of(ChatMessage.system("You are John."), ChatMessage.user("Hi")))).getAsJsonObject();

        assertEquals("test-model", body.get("model").getAsString());
        assertEquals(2000, body.get("max_tokens").getAsInt());
        assertEquals(0.7, body.get("temperature").getAsDouble());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Hi", body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void mergesExtraParamsAndLetsThemOverride() {
        JsonObject body = JsonParser.parseString(ChatRequests.toJson(
                settings("{\"thinking\":{\"type\":\"disabled\"},\"temperature\":1.3}"),
                List.of(ChatMessage.user("Hi")))).getAsJsonObject();

        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(1.3, body.get("temperature").getAsDouble());
    }

    @Test
    void neverPutsTheKeyIntoTheBodyOrToString() {
        LlmSettings settings = settings("{}");

        assertFalse(ChatRequests.toJson(settings, List.of(ChatMessage.user("Hi"))).contains("secret-key"));
        assertFalse(settings.toString().contains("secret-key"));
    }
}
