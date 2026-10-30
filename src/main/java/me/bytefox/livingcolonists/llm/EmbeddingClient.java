package me.bytefox.livingcolonists.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Vectors of texts from an OpenAI-compatible {@code /embeddings} API, so remembered facts can be
 * found by meaning, in any language. Only asynchronous: the future completes on the HTTP client's
 * threads and fails on any problem; callers fall back to finding facts by words. No retries: a
 * vector that comes late is no use to a reply.
 */
public final class EmbeddingClient {
    private final HttpClient http;
    private final UsageStats stats;

    public EmbeddingClient(HttpClient http, UsageStats stats) {
        this.http = http;
        this.stats = stats;
    }

    /** One vector per text, in their order. */
    public CompletableFuture<List<float[]>> embed(LlmEndpoint endpoint, List<String> texts, Duration timeout) {
        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        JsonArray input = new JsonArray();
        texts.forEach(input::add);
        body.add("input", input);
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(uri(endpoint.baseUrl())).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid embeddings baseUrl"));
        }
        if (!endpoint.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        long start = System.nanoTime();
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    if (response.statusCode() != 200) {
                        stats.record(endpoint.model(), new ChatResult.Failure("HTTP " + response.statusCode(), false), took);
                        throw new IllegalStateException("embeddings HTTP " + response.statusCode());
                    }
                    List<float[]> vectors = parse(response.body(), texts.size());
                    stats.record(endpoint.model(), new ChatResult.Reply("", new Usage(promptTokens(response.body()), 0, 0)),
                            took);
                    return vectors;
                });
    }

    /** The vectors of an {@code /embeddings} answer, in input order; fails unless there is one per text. */
    public static List<float[]> parse(String body, int expected) {
        JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
        if (data == null || data.size() != expected) {
            throw new IllegalStateException("embeddings answer has " + (data == null ? 0 : data.size()) + " of "
                    + expected + " vectors");
        }
        List<float[]> vectors = new ArrayList<>(Collections.nCopies(expected, (float[]) null));
        for (int i = 0; i < data.size(); i++) {
            JsonObject item = data.get(i).getAsJsonObject();
            int index = item.has("index") ? item.get("index").getAsInt() : i;
            JsonArray values = item.getAsJsonArray("embedding");
            float[] vector = new float[values.size()];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = values.get(j).getAsFloat();
            }
            vectors.set(index, vector);
        }
        return vectors;
    }

    /** How alike two vectors point, -1 to 1; 0 if they don't fit together. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }

    private static long promptTokens(String body) {
        JsonElement usage = JsonParser.parseString(body).getAsJsonObject().get("usage");
        return usage != null && usage.isJsonObject() && usage.getAsJsonObject().has("prompt_tokens")
                ? usage.getAsJsonObject().get("prompt_tokens").getAsLong() : 0;
    }

    private static URI uri(String baseUrl) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + "/embeddings");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("not an absolute URL");
        }
        return uri;
    }
}
