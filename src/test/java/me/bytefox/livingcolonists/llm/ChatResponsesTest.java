package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponsesTest {
    private static String completion(String content, String finishReason) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":%s,
                "reasoning_content":"thinking..."},"finish_reason":"%s"}],
                "usage":{"prompt_tokens":70,"completion_tokens":76,"prompt_cache_hit_tokens":64}}
                """.formatted(content, finishReason);
    }

    @Test
    void returnsTheReplyTextAndUsage() {
        ChatResult.Reply reply = assertInstanceOf(ChatResult.Reply.class,
                ChatResponses.parse(200, completion("\"  Stone, eight of it.  \"", "stop")));

        assertEquals("Stone, eight of it.", reply.text());
        assertEquals(new Usage(70, 76, 64), reply.usage());
    }

    @Test
    void readsOpenAiStyleCachedTokens() {
        String body = """
                {"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":8}}}""";

        assertEquals(new Usage(10, 2, 8), ((ChatResult.Reply) ChatResponses.parse(200, body)).usage());
    }

    @Test
    void missingUsageCountsAsZero() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}";

        assertEquals(Usage.NONE, ((ChatResult.Reply) ChatResponses.parse(200, body)).usage());
    }

    @Test
    void emptyContentIsAFailureThatNamesTheFinishReason() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                ChatResponses.parse(200, completion("\"\"", "length")));

        assertTrue(failure.reason().contains("length"), failure.reason());
        assertFalse(failure.retryable());
    }

    @Test
    void anEmptyReplyThatJustStoppedIsWorthAnotherTry() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                ChatResponses.parse(200, completion("\"\"", "stop")));

        assertTrue(failure.retryable());
    }

    @Test
    void aReplyCutOffByTheTokenLimitKeepsItsWholeSentences() {
        ChatResult.Reply reply = assertInstanceOf(ChatResult.Reply.class, ChatResponses.parse(200,
                completion("\"Ask Max. I promised not to tell, so I won't give it awa\"", "length")));
        assertEquals("Ask Max.", reply.text());
    }

    @Test
    void aReplyCutOffBeforeItsFirstSentenceEndsIsAFailure() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200,
                completion("\"Ask Max, I promised not to te\"", "length")));
        assertTrue(failure.reason().contains("cut off"), failure.reason());
    }

    @Test
    void nullContentIsAFailure() {
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, completion("null", "stop")));
    }

    @Test
    void authenticationErrorsAreNamedAndNotRetried() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                ChatResponses.parse(401, "{\"error\":{\"message\":\"Authentication Fails\"}}"));

        assertTrue(failure.reason().contains("401"), failure.reason());
        assertTrue(failure.reason().contains("Authentication Fails"), failure.reason());
        assertFalse(failure.retryable());
        assertFalse(((ChatResult.Failure) ChatResponses.parse(400, "")).retryable());
    }

    @Test
    void rateLimitsAndServerErrorsAreRetried() {
        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(429, ""));

        assertTrue(failure.reason().contains("429"), failure.reason());
        assertTrue(failure.retryable());
        assertTrue(((ChatResult.Failure) ChatResponses.parse(503, "")).retryable());
    }

    @Test
    void malformedBodyIsAFailure() {
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, "<html>bad gateway</html>"));
        assertInstanceOf(ChatResult.Failure.class, ChatResponses.parse(200, "{\"choices\":[]}"));
    }
}
