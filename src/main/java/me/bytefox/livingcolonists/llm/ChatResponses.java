package me.bytefox.livingcolonists.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Turns an HTTP status and body of {@code /chat/completions} into a {@link ChatResult}. */
public final class ChatResponses {
    private static final int MAX_ERROR_LENGTH = 200;

    private ChatResponses() {
    }

    public static ChatResult parse(int status, String body) {
        if (status != 200) {
            return new ChatResult.Failure(httpError(status, body), status == 408 || status == 429 || status >= 500);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return new ChatResult.Failure("response has no choices", false);
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            JsonElement content = message.get("content");
            List<ToolCall> calls = toolCalls(message.get("tool_calls"));
            if (!calls.isEmpty()) {
                return new ChatResult.Reply(content == null || content.isJsonNull() ? "" : content.getAsString().strip(),
                        usage(root.getAsJsonObject("usage")), calls);
            }
            if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                // A reasoning model sometimes stops with nothing to say; the same request usually works
                // the next time. Cut off by max_tokens, it would only be cut off again.
                String finish = string(choice.get("finish_reason"));
                return new ChatResult.Failure("empty reply, finish_reason=" + finish, "stop".equals(finish));
            }
            String text = content.getAsString().strip();
            if ("length".equals(string(choice.get("finish_reason")))) {
                // Cut off by max_tokens (a reasoning model thought too long): keep the sentences that ended.
                text = wholeSentences(text);
                if (text.isEmpty()) {
                    return new ChatResult.Failure("reply cut off by max_tokens before its first sentence ended", false);
                }
            }
            return new ChatResult.Reply(text, usage(root.getAsJsonObject("usage")));
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            return new ChatResult.Failure("malformed response: " + truncate(body), false);
        }
    }

    /** The text up to its last sentence end (. ! ? …), or empty if no sentence ended. */
    static String wholeSentences(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (".!?…".indexOf(text.charAt(i)) >= 0) {
                return text.substring(0, i + 1).strip();
            }
        }
        return "";
    }

    private static List<ToolCall> toolCalls(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            JsonObject call = item.getAsJsonObject();
            JsonObject function = call.getAsJsonObject("function");
            if (function != null) {
                calls.add(new ToolCall(string(call.get("id")), string(function.get("name")),
                        string(function.get("arguments"))));
            }
        }
        return calls;
    }

    /**
     * Token counts. Cached prompt tokens are {@code prompt_cache_hit_tokens} on DeepSeek and
     * {@code prompt_tokens_details.cached_tokens} on OpenAI-style APIs.
     */
    private static Usage usage(JsonObject usage) {
        if (usage == null) {
            return Usage.NONE;
        }
        long cached = number(usage.get("prompt_cache_hit_tokens"));
        JsonElement details = usage.get("prompt_tokens_details");
        if (cached == 0 && details != null && details.isJsonObject()) {
            cached = number(details.getAsJsonObject().get("cached_tokens"));
        }
        return new Usage(number(usage.get("prompt_tokens")), number(usage.get("completion_tokens")), cached);
    }

    private static long number(JsonElement element) {
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsLong();
    }

    private static String httpError(int status, String body) {
        String message = errorMessage(body);
        String reason = switch (status) {
            case 401, 403 -> "authentication failed";
            case 402 -> "payment required";
            case 429 -> "rate limited";
            default -> status >= 500 ? "provider error" : "request rejected";
        };
        return "HTTP " + status + " " + reason + (message.isEmpty() ? "" : ": " + message);
    }

    private static String errorMessage(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            return error == null ? truncate(body) : truncate(string(error.get("message")));
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            return truncate(body);
        }
    }

    private static String string(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String truncate(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_ERROR_LENGTH ? flat : flat.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
