package me.bytefox.livingcolonists.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The models a provider offers, from {@code GET /models}, for the settings screen.
 *
 * @param error why the list couldn't be read; null when it was
 */
public record ModelList(List<String> models, String error) {
    public static ModelList failed(String error) {
        return new ModelList(List.of(), error);
    }

    public static ModelList parse(int status, String body) {
        if (status != 200) {
            return failed(ChatResponses.parse(status, body) instanceof ChatResult.Failure failure ? failure.reason()
                    : "HTTP " + status);
        }
        try {
            List<String> ids = new ArrayList<>();
            for (JsonElement model : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data")) {
                JsonObject object = model.getAsJsonObject();
                if (object.has("id")) {
                    ids.add(object.get("id").getAsString());
                }
            }
            ids.sort(String::compareTo);
            return new ModelList(List.copyOf(ids), null);
        } catch (JsonParseException | IllegalStateException | ClassCastException | NullPointerException e) {
            return failed("malformed answer from /models");
        }
    }
}
