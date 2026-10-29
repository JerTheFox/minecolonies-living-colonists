package me.bytefox.livingcolonists.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Asynchronous client for an OpenAI-compatible chat completions API.
 *
 * <p>The returned future completes on an HTTP client thread and never completes exceptionally:
 * every problem becomes a {@link ChatResult.Failure}. Callers must hop back to the server thread
 * before touching the world.</p>
 */
public final class LlmClient {
    private final HttpClient http;

    public LlmClient(HttpClient http) {
        this.http = http;
    }

    public CompletableFuture<ChatResult> complete(LlmSettings settings, List<ChatMessage> messages) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint(settings.baseUrl()))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(ChatRequests.toJson(settings, messages), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new ChatResult.Failure("invalid baseUrl: " + settings.baseUrl()));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> ChatResponses.parse(response.statusCode(), response.body()))
                .exceptionally(e -> new ChatResult.Failure(describe(e, settings.timeoutSeconds())));
    }

    private static URI endpoint(String baseUrl) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + "/chat/completions");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("not an absolute URL");
        }
        return uri;
    }

    private static String describe(Throwable error, int timeoutSeconds) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) {
            return "timed out after " + timeoutSeconds + " s";
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
