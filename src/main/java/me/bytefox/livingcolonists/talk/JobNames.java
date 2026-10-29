package me.bytefox.livingcolonists.talk;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.jobs.IJob;
import me.bytefox.livingcolonists.LivingColonists;
import net.neoforged.fml.ModList;

/**
 * The words players may use for a colonist's profession: its names from the translations of the
 * mod that adds the job (in English, Russian and the player's own locale) and a few everyday words
 * the translations don't use, like "cook" for MineColonies' waiter or "guards" for all of them.
 */
public final class JobNames {
    private static final List<String> GUARD = List.of("guard", "soldier", "стражник", "страж", "стража", "охранник",
            "охрана", "солдат");
    private static final Map<String, List<String>> EVERYDAY = Map.ofEntries(
            Map.entry("ranger", GUARD), Map.entry("knight", GUARD), Map.entry("druid", GUARD),
            Map.entry("huscarl", GUARD), Map.entry("marksman", GUARD), Map.entry("cavalry", GUARD),
            Map.entry("chef", List.of("cook", "кок", "кухарка")),
            Map.entry("cook", List.of("waitress", "официантка")),
            Map.entry("healer", List.of("healer", "medic", "врач", "доктор", "целитель", "лекарь")),
            Map.entry("deliveryman", List.of("delivery", "deliveryman", "доставщик", "грузчик", "носильщик")),
            Map.entry("lumberjack", List.of("lumberjack", "woodcutter", "дровосек")),
            Map.entry("fisherman", List.of("fisherman", "рыболов")),
            Map.entry("researcher", List.of("scientist", "scholar", "учёный")),
            Map.entry("sawmill", List.of("столяр")),
            Map.entry("quarrier", List.of("карьерщик")),
            Map.entry("smelter", List.of("плавильщик")),
            Map.entry("undertaker", List.of("гробовщик")),
            Map.entry("cowboy", List.of("cowboy", "ковбой")),
            Map.entry("enchanter", List.of("зачарователь")),
            Map.entry("planter", List.of("gardener", "садовод", "садовник")),
            Map.entry("florist", List.of("цветочник", "цветочница")),
            Map.entry("beekeeper", List.of("пчеловод")));
    /** Translations by mod and locale, read once from the mods' own language files. */
    private final Map<String, Map<String, String>> translations = new HashMap<>();

    /** The words for this colonist's profession; empty for the unemployed and children. */
    public Set<String> of(ICitizenData citizen, String locale) {
        IJob<?> job = citizen.getJob();
        if (job == null) {
            return Set.of();
        }
        var entry = job.getJobRegistryEntry();
        Set<String> words = new LinkedHashSet<>();
        for (String language : new LinkedHashSet<>(List.of("en_us", "ru_ru", locale))) {
            String name = translations(entry.getKey().getNamespace(), language).get(entry.getTranslationKey());
            if (name != null && !name.isBlank()) {
                words.add(name);
            }
        }
        words.addAll(EVERYDAY.getOrDefault(entry.getKey().getPath(), List.of()));
        return words;
    }

    private Map<String, String> translations(String modId, String locale) {
        return translations.computeIfAbsent(modId + "/" + locale, key -> read(modId, locale));
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
