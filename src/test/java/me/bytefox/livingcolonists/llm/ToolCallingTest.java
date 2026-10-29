package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ToolCallingTest {
    private static final LlmEndpoint ENDPOINT = new LlmEndpoint("https://api.example.com", "m", "k");
    private static final LlmSettings SETTINGS = new LlmSettings(ENDPOINT, null, 30, 100, 1.0, new JsonObject(), 0);
    private static final ToolSpec SPEC = new ToolSpec("best_for_job", "Who fits a profession.",
            JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"profession\":{\"type\":\"string\"}}}").getAsJsonObject());

    @Test
    void requestCarriesToolsAndTheToolExchange() {
        ToolCall call = new ToolCall("call_1", "best_for_job", "{\"profession\":\"builder\"}");
        JsonObject body = JsonParser.parseString(ChatRequests.toJson(ENDPOINT, SETTINGS, List.of(
                ChatMessage.user("Who should build?"),
                ChatMessage.assistantCalling(List.of(call)),
                ChatMessage.toolResult("call_1", "{\"ranking\":[]}")), List.of(SPEC))).getAsJsonObject();

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals("function", tools.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("best_for_job", tools.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());

        JsonObject assistant = body.getAsJsonArray("messages").get(1).getAsJsonObject();
        JsonObject function = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("best_for_job", function.get("name").getAsString());
        assertEquals("{\"profession\":\"builder\"}", function.get("arguments").getAsString());

        JsonObject tool = body.getAsJsonArray("messages").get(2).getAsJsonObject();
        assertEquals("tool", tool.get("role").getAsString());
        assertEquals("call_1", tool.get("tool_call_id").getAsString());
    }

    @Test
    void noToolsMeansNoToolsField() {
        JsonObject body = JsonParser.parseString(ChatRequests.toJson(ENDPOINT, SETTINGS,
                List.of(ChatMessage.user("Hi")), List.of())).getAsJsonObject();
        assertFalse(body.has("tools"));
    }

    @Test
    void parsesToolCalls() {
        String response = """
                {"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"...",
                "tool_calls":[{"id":"call_1","type":"function","function":{"name":"best_for_job",
                "arguments":"{\\"profession\\": \\"builder\\"}"}}]},"finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

        ChatResult.Reply reply = assertInstanceOf(ChatResult.Reply.class, ChatResponses.parse(200, response));

        assertEquals(List.of(new ToolCall("call_1", "best_for_job", "{\"profession\": \"builder\"}")), reply.toolCalls());
        assertTrue(reply.wantsTools());
    }

    @Test
    void anOrdinaryReplyHasNoToolCalls() {
        String response = "{\"choices\":[{\"message\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}";
        ChatResult.Reply reply = assertInstanceOf(ChatResult.Reply.class, ChatResponses.parse(200, response));
        assertFalse(reply.wantsTools());
    }
}
