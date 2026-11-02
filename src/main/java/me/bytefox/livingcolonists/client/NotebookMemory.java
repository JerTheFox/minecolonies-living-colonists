package me.bytefox.livingcolonists.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.ModLog;
import net.minecraft.client.Minecraft;

/**
 * What the Colony Notebook remembers on this client, per server or world: where it was closed (section and spread) and
 * what each section's news was when the player last looked, so a tab is marked only for something new. Kept in
 * {@code config/livingcolonists-notebook.json}; losing it only loses the bookmark and the marks.
 */
final class NotebookMemory {
    private static JsonObject all;

    private NotebookMemory() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("livingcolonists-notebook.json");
    }

    /** This server's or world's part of the file. */
    private static JsonObject here() {
        if (all == null) {
            all = new JsonObject();
            try {
                if (Files.exists(file())) {
                    all = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
            } catch (IOException | RuntimeException e) {
                ModLog.warn("Couldn't read {}, starting the notebook afresh: {}", file(), e.toString());
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        String where = minecraft.getCurrentServer() != null ? "server " + minecraft.getCurrentServer().ip
                : minecraft.getSingleplayerServer() != null ? "world " + minecraft.getSingleplayerServer().getWorldData().getLevelName()
                : "unknown";
        if (!all.has(where)) {
            all.add(where, new JsonObject());
        }
        return all.getAsJsonObject(where);
    }

    static String section() {
        JsonObject here = here();
        return here.has("section") ? here.get("section").getAsString() : "";
    }

    static int spread() {
        JsonObject here = here();
        return here.has("spread") ? here.get("spread").getAsInt() : 0;
    }

    static void bookmark(String section, int spread) {
        JsonObject here = here();
        here.addProperty("section", section);
        here.addProperty("spread", spread);
    }

    /** Whether the section's news differs from what the player last looked at. */
    static boolean isNew(String section, String news) {
        if (news.isEmpty()) {
            return false;
        }
        JsonObject seen = here().getAsJsonObject("seen");
        return seen == null || !seen.has(section) || !seen.get(section).getAsString().equals(news);
    }

    static void seen(String section, String news) {
        JsonObject here = here();
        if (!here.has("seen")) {
            here.add("seen", new JsonObject());
        }
        here.getAsJsonObject("seen").addProperty(section, news);
    }

    /** Written when the notebook closes. */
    static void save() {
        if (all == null) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), all.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModLog.warn("Couldn't save {}: {}", file(), e.toString());
        }
    }
}
