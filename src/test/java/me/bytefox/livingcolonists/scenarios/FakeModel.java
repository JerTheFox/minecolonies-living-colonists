package me.bytefox.livingcolonists.scenarios;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * A language model provider on this machine for scenario tests: answers chat requests with replies queued by the
 * test, each for the first request that matches it, and keeps every request to look at. Embeddings get a fixed
 * vector. Requests nothing was queued for get a plain short line.
 */
final class FakeModel implements AutoCloseable {
    /** A reply waiting for a request it fits. */
    private record Queued(Predicate<JsonObject> fits, String body) {
    }

    private final HttpServer server;
    private final Queue<Queued> replies = new ConcurrentLinkedQueue<>();
    final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    /** What a colonist says when nothing was queued for the request: set it to one line for a model that's stuck. */
    volatile String replyLine = "Ладно.";
    /** Texts sent for vectors, each input once per request. */
    final List<String> embedded = new CopyOnWriteArrayList<>();

    FakeModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            String body;
            if (path.endsWith("/embeddings")) {
                JsonArray data = new JsonArray();
                var input = request.get("input");
                int count = input.isJsonArray() ? input.getAsJsonArray().size() : 1;
                if (input.isJsonArray()) {
                    input.getAsJsonArray().forEach(text -> embedded.add(text.getAsString()));
                } else {
                    embedded.add(input.getAsString());
                }
                for (int i = 0; i < count; i++) {
                    JsonObject vector = new JsonObject();
                    vector.add("embedding", JsonParser.parseString("[0.1,0.2,0.3]"));
                    vector.addProperty("index", i);
                    data.add(vector);
                }
                JsonObject answer = new JsonObject();
                answer.add("data", data);
                answer.add("usage", JsonParser.parseString("{\"prompt_tokens\":1}"));
                body = answer.toString();
            } else {
                requests.add(request);
                body = take(request);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** The next request offering the function {@code tool} gets a call of it with these arguments. */
    void callsWhenOffered(String tool, JsonObject arguments) {
        JsonObject call = new JsonObject();
        call.addProperty("id", "call-" + tool);
        call.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", tool);
        function.addProperty("arguments", arguments.toString());
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject message = new JsonObject();
        message.add("tool_calls", calls);
        replies.add(new Queued(request -> isReply(request) && offers(request, tool), completion(message, "tool_calls")));
    }

    /** The next colonist's reply whose request contains {@code text} is this line (not routing, not memory). */
    void saysWhenAsked(String text, String line) {
        JsonObject message = new JsonObject();
        message.addProperty("content", line);
        replies.add(new Queued(request -> isReply(request) && request.toString().contains(text), completion(message, "stop")));
    }

    /** The next request of any kind (memory, routing, stories) whose text contains {@code text} gets {@code content}. */
    void answersWhen(String text, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        replies.add(new Queued(request -> request.toString().contains(text), completion(message, "stop")));
    }

    /** The next request to write down a talk whose lines contain {@code text} gets {@code content}. */
    void remembersWhen(String text, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        replies.add(new Queued(request -> request.toString().contains("You keep the memory of") && request.toString().contains(text),
                completion(message, "stop")));
    }

    /** A request for a colonist's line: the reply templates start by casting the model as a colonist. */
    static boolean isReply(JsonObject request) {
        return request.toString().contains("You play a colonist");
    }

    static boolean offers(JsonObject request, String tool) {
        if (!request.has("tools")) {
            return false;
        }
        for (var entry : request.getAsJsonArray("tools")) {
            if (tool.equals(entry.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private String take(JsonObject request) {
        for (Queued queued : replies) {
            if (queued.fits().test(request) && replies.remove(queued)) {
                return queued.body();
            }
        }
        JsonObject message = new JsonObject();
        message.addProperty("content", isReply(request) ? replyLine : "[nothing]");
        return completion(message, "stop");
    }

    private static String completion(JsonObject message, String finish) {
        JsonObject choice = new JsonObject();
        message.addProperty("role", "assistant");
        choice.add("message", message);
        choice.addProperty("finish_reason", finish);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 2);
        JsonObject body = new JsonObject();
        body.add("choices", choices);
        body.add("usage", usage);
        return body.toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
