package me.bytefox.livingcolonists.llm;

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
            return new ChatResult.Failure(httpError(status, body));
        }
        try {
            JsonArray choices = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return new ChatResult.Failure("response has no choices");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonElement content = choice.getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                return new ChatResult.Failure("empty reply, finish_reason=" + string(choice.get("finish_reason")));
            }
            return new ChatResult.Reply(content.getAsString().strip());
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            return new ChatResult.Failure("malformed response: " + truncate(body));
        }
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
