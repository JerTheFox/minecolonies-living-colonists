package me.bytefox.livingcolonists.talk;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.jobs.IJob;

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

    /** The words for this colonist's profession; empty for the unemployed and children. */
    public Set<String> of(ICitizenData citizen, String locale) {
        IJob<?> job = citizen.getJob();
        if (job == null) {
            return Set.of();
        }
        var entry = job.getJobRegistryEntry();
        Set<String> words = new LinkedHashSet<>();
        for (String language : new LinkedHashSet<>(List.of("en_us", "ru_ru", locale))) {
            String name = Translations.of(entry.getKey().getNamespace(), language).get(entry.getTranslationKey());
            if (name != null && !name.isBlank()) {
                words.add(name);
            }
        }
        words.addAll(EVERYDAY.getOrDefault(entry.getKey().getPath(), List.of()));
        return words;
    }
}
