package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponsesTest {
    private static String completion(String content, String finishReason) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":%s,
                "reasoning_content":"thinking..."},"finish_reason":"%s"}]}
                """.formatted(content, finishReason);
    }

    @Test
    void returnsTheReplyText() {
        ChatResult result = ChatResponses.parse(200, completion("\"  Stone, eight of it.  \"", "stop"));

        assertEquals(new ChatResult.Reply("Stone, eight of it."), result);
    }

    @Test
    void emptyContentIsAFailureThatNamesTheFinishReason() {
        ChatResult result = ChatResponses.parse(200, completion("\"\"", "length"));

        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class, result);
        assertTrue(failure.reason().contains("length"), failure.reason());
    }

    @Test
    void nullContentIsAFailure() {
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, completion("null", "stop")));
    }

    @Test
    void authenticationErrorsAreNamed() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                ChatResponses.parse(401, "{\"error\":{\"message\":\"Authentication Fails\"}}"));

        assertTrue(failure.reason().contains("401"), failure.reason());
        assertTrue(failure.reason().contains("Authentication Fails"), failure.reason());
    }

    @Test
    void rateLimitIsNamed() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(429, ""));

        assertTrue(failure.reason().contains("429"), failure.reason());
    }

    @Test
    void malformedBodyIsAFailure() {
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, "<html>bad gateway</html>"));
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, "{\"choices\":[]}"));
    }
}
