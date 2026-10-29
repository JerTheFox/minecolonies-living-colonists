package me.bytefox.livingcolonists.llm;

import java.io.IOException;
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
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous client for an OpenAI-compatible chat completions API.
 *
 * <p>Temporary failures (timeouts, rate limits, server errors) are retried with a pause; when the
 * primary endpoint runs out of retries, the fallback endpoint gets its turn. Every attempt is
 * counted in {@link UsageStats}.</p>
 *
 * <p>The returned future completes on an HTTP client thread and never completes exceptionally:
 * every problem becomes a {@link ChatResult.Failure}. Callers must hop back to the server thread
 * before touching the world.</p>
 */
public final class LlmClient {
    /** Pauses before the first, second and later retries. */
    public static final List<Duration> DEFAULT_BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    private final HttpClient http;
    private final UsageStats stats;
    private final List<Duration> backoff;

    public LlmClient(HttpClient http, UsageStats stats, List<Duration> backoff) {
        this.http = http;
        this.stats = stats;
        this.backoff = List.copyOf(backoff);
    }

    public CompletableFuture<ChatResult> complete(LlmSettings settings, List<ChatMessage> messages) {
        return withRetries(settings.primary(), settings, messages, 0).thenCompose(result -> {
            if (result instanceof ChatResult.Failure && settings.fallback() != null) {
                return withRetries(settings.fallback(), settings, messages, 0);
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<ChatResult> withRetries(LlmEndpoint endpoint, LlmSettings settings, List<ChatMessage> messages,
                                                      int attempt) {
        return once(endpoint, settings, messages).thenCompose(result -> {
            if (result instanceof ChatResult.Failure failure && failure.retryable() && attempt < settings.retries()) {
                Duration pause = backoff.get(Math.min(attempt, backoff.size() - 1));
                return CompletableFuture.runAsync(() -> { },
                                CompletableFuture.delayedExecutor(pause.toMillis(), TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> withRetries(endpoint, settings, messages, attempt + 1));
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<ChatResult> once(LlmEndpoint endpoint, LlmSettings settings, List<ChatMessage> messages) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(endpoint.baseUrl()))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(ChatRequests.toJson(endpoint, settings, messages),
                            StandardCharsets.UTF_8));
            if (!endpoint.apiKey().isEmpty()) {
                builder.header("Authorization", "Bearer " + endpoint.apiKey());
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new ChatResult.Failure("invalid baseUrl: " + endpoint.baseUrl(), false));
        }
        long start = System.nanoTime();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> ChatResponses.parse(response.statusCode(), response.body()))
                .exceptionally(e -> describe(e, settings.timeoutSeconds()))
                .thenApply(result -> {
                    stats.record(endpoint.model(), result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    return result;
                });
    }

    private static URI uri(String baseUrl) {
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

    private static ChatResult.Failure describe(Throwable error, int timeoutSeconds) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) {
            return new ChatResult.Failure("timed out after " + timeoutSeconds + " s", true);
        }
        String reason = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return new ChatResult.Failure(reason, cause instanceof IOException);
    }
}
