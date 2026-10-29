package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmClientTest {
    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"Привет\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

    /** A fake provider that answers with queued status codes, then 200. */
    private static final class FakeProvider {
        final HttpServer server;
        final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> auth = new AtomicReference<>();
        final AtomicReference<String> body = new AtomicReference<>();
        volatile long delayMillis;

        FakeProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                path.set(exchange.getRequestURI().getPath());
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Integer status = statuses.poll();
                byte[] bytes = (status == null ? OK : "{\"error\":{\"message\":\"nope\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status == null ? 200 : status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        LlmEndpoint endpoint(String model, String key) {
            return new LlmEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", model, key);
        }
    }

    private FakeProvider primary;
    private FakeProvider fallback;
    private final UsageStats stats = new UsageStats();
    private LlmClient client;

    @BeforeEach
    void start() throws IOException {
        primary = new FakeProvider();
        fallback = new FakeProvider();
        client = new LlmClient(HttpClient.newHttpClient(), stats, List.of(Duration.ofMillis(10), Duration.ofMillis(20)));
    }

    @AfterEach
    void stop() {
        primary.server.stop(0);
        fallback.server.stop(0);
    }

    private LlmSettings settings(int timeoutSeconds, int retries, LlmEndpoint fallbackEndpoint) {
        return new LlmSettings(primary.endpoint("chat-model", "secret-key"), fallbackEndpoint, timeoutSeconds, 100, 1.0,
                new JsonObject(), retries);
    }

    @Test
    void postsToChatCompletionsWithBearerKey() {
        ChatResult result = client.complete(settings(5, 2, null), List.of(ChatMessage.user("Здравствуй"))).join();

        assertEquals(new ChatResult.Reply("Привет", new Usage(12, 3, 0)), result);
        assertEquals("/v1/chat/completions", primary.path.get());
        assertEquals("Bearer secret-key", primary.auth.get());
        assertTrue(primary.body.get().contains("Здравствуй"), primary.body.get());
        assertTrue(primary.body.get().contains("chat-model"), primary.body.get());
    }

    @Test
    void retriesTemporaryFailures() {
        primary.statuses.add(503);
        primary.statuses.add(429);

        ChatResult result = client.complete(settings(5, 2, null), List.of(ChatMessage.user("Hi"))).join();

        assertInstanceOf(ChatResult.Reply.class, result);
        assertEquals(3, primary.requests.get());
        assertEquals(3, stats.session().get("chat-model").requests());
        assertEquals(2, stats.session().get("chat-model").failures());
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        primary.statuses.add(500);
        primary.statuses.add(500);
        primary.statuses.add(500);

        assertInstanceOf(ChatResult.Failure.class,
                client.complete(settings(5, 1, null), List.of(ChatMessage.user("Hi"))).join());
        assertEquals(2, primary.requests.get());
    }

    @Test
    void doesNotRetryAuthenticationErrors() {
        primary.statuses.add(401);

        assertInstanceOf(ChatResult.Failure.class,
                client.complete(settings(5, 2, null), List.of(ChatMessage.user("Hi"))).join());
        assertEquals(1, primary.requests.get());
    }

    @Test
    void usesTheFallbackWhenThePrimaryFails() {
        primary.statuses.add(500);
        primary.statuses.add(500);

        ChatResult result = client.complete(settings(5, 1, fallback.endpoint("local-model", "")),
                List.of(ChatMessage.user("Hi"))).join();

        assertInstanceOf(ChatResult.Reply.class, result);
        assertEquals(1, fallback.requests.get());
        assertTrue(fallback.body.get().contains("local-model"), fallback.body.get());
        assertEquals(null, fallback.auth.get());
        assertEquals(1, stats.session().get("local-model").requests());
    }

    @Test
    void timeoutBecomesARetryableFailureWithoutTheKey() {
        primary.delayMillis = 2500;

        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                client.complete(settings(1, 0, null), List.of(ChatMessage.user("Hi"))).join());

        assertTrue(failure.reason().contains("timed out"), failure.reason());
        assertTrue(failure.retryable());
        assertFalse(failure.reason().contains("secret-key"), failure.reason());
    }

    @Test
    void invalidBaseUrlBecomesAFailure() {
        LlmSettings broken = new LlmSettings(new LlmEndpoint("not a url", "m", "k"), null, 5, 100, 1.0, new JsonObject(), 2);

        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class,
                client.complete(broken, List.of(ChatMessage.user("Hi"))).join());
        assertFalse(failure.retryable());
    }
}
