package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmClientTest {
    private HttpServer server;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"choices\":[{\"message\":{\"content\":\"Привет\"},\"finish_reason\":\"stop\"}]}";
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmSettings settings(int timeoutSeconds) {
        return new LlmSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "test-model", "secret-key",
                timeoutSeconds, 100, 1.0, new JsonObject());
    }

    @Test
    void postsToChatCompletionsWithBearerKey() {
        ChatResult result = new LlmClient(HttpClient.newHttpClient())
                .complete(settings(5), List.of(ChatMessage.user("Здравствуй"))).join();

        assertEquals(new ChatResult.Reply("Привет"), result);
        assertEquals("/v1/chat/completions", seenPath.get());
        assertEquals("Bearer secret-key", seenAuth.get());
        assertTrue(seenBody.get().contains("Здравствуй"), seenBody.get());
    }

    @Test
    void httpErrorsBecomeFailures() {
        status = 401;
        reply = "{\"error\":{\"message\":\"Authentication Fails\"}}";

        ChatResult result = new LlmClient(HttpClient.newHttpClient())
                .complete(settings(5), List.of(ChatMessage.user("Hi"))).join();

        assertInstanceOf(ChatResult.Failure.class, result);
    }

    @Test
    void timeoutBecomesAFailureWithoutTheKey() {
        delayMillis = 2500;

        ChatResult.Failure failure = assertInstanceOf(ChatResult.Failure.class, new LlmClient(HttpClient.newHttpClient())
                .complete(settings(1), List.of(ChatMessage.user("Hi"))).join());

        assertTrue(failure.reason().contains("timed out"), failure.reason());
        assertTrue(!failure.reason().contains("secret-key"), failure.reason());
    }

    @Test
    void invalidBaseUrlBecomesAFailure() {
        LlmSettings broken = new LlmSettings("not a url", "m", "k", 5, 100, 1.0, new JsonObject());

        assertInstanceOf(ChatResult.Failure.class,
                new LlmClient(HttpClient.newHttpClient()).complete(broken, List.of(ChatMessage.user("Hi"))).join());
    }
}
