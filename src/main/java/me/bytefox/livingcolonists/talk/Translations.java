package me.bytefox.livingcolonists.talk;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.LivingColonists;
import net.neoforged.fml.ModList;

/**
 * Translations read from the mods' own language files, for words players use in other languages
 * than the server's English. A dedicated server has only English for vanilla Minecraft; mods ship
 * all their languages in their jars.
 */
public final class Translations {
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private Translations() {
    }

    /** All translations of a mod in one locale, like {@code ru_ru}; empty if the mod has none. */
    public static Map<String, String> of(String modId, String locale) {
        return CACHE.computeIfAbsent(modId + "/" + locale, key -> read(modId, locale));
    }

    private static Map<String, String> read(String modId, String locale) {
        var file = ModList.get().getModFileById(modId);
        if (file == null) {
            return Map.of();
        }
        Path path = file.getFile().findResource("assets", modId, "lang", locale + ".json");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e -> {
                if (e.getValue().isJsonPrimitive()) {
                    names.put(e.getKey(), e.getValue().getAsString());
                }
            });
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LivingColonists.LOGGER.warn("Could not read {} translations of {}: {}", locale, modId, e.getMessage());
        }
        return names;
    }
}
