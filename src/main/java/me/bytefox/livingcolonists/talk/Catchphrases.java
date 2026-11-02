package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Catchphrases (#65): now and then a colonist says a set phrase, NPC-style, and another may answer with its set reply,
 * from {@code config/livingcolonists/content/catchphrases.json}: everyday sayings, and nods to games and films (short,
 * well-known lines, said as the colonist's own; colonists don't know where they come from). Rare on purpose, so colonists
 * don't talk in quotes: a small chance per line of colonists' own talk, and cooldowns per colony, per colonist and per
 * phrase. The text is said as it is in the file, without the model; {@code {enemy}} and {@code {friend}} take the name of
 * someone the speaker can't stand or is close to. Cooldowns are kept in memory. Server thread only.
 */
public final class Catchphrases {
    /**
     * @param replier who should answer with {@code reply}: the colonist named in the line, or null for anyone
     */
    public record Said(String line, String reply, String replier) {
    }

    private record Pair(String line, String reply) {
    }

    private record Language(List<Pair> pairs, List<String> heard) {
    }

    private static final long MINUTE = 60 * 20;
    private static final long DAY = 24000;

    private static volatile double chance;
    private static volatile long colonyCooldown;
    private static volatile long colonistCooldown;
    private static volatile long phraseCooldown;
    private static volatile double replyChance;
    private static volatile double heardChance;
    private static volatile Map<String, Language> languages = Map.of();

    private final Map<Integer, Long> lastInColony = new HashMap<>();
    private final Map<String, Long> lastByColonist = new HashMap<>();
    private final Map<String, Long> lastOfPhrase = new HashMap<>();

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        chance = root.get("chance").getAsDouble();
        colonyCooldown = Math.round(root.get("colonyCooldownMinutes").getAsDouble() * MINUTE);
        colonistCooldown = Math.round(root.get("colonistCooldownDays").getAsDouble() * DAY);
        phraseCooldown = Math.round(root.get("phraseCooldownDays").getAsDouble() * DAY);
        replyChance = root.get("replyChance").getAsDouble();
        heardChance = root.get("heardChance").getAsDouble();
        Map<String, Language> read = new HashMap<>();
        root.getAsJsonObject("languages").entrySet().forEach(entry -> {
            JsonObject language = entry.getValue().getAsJsonObject();
            List<Pair> pairs = new ArrayList<>();
            // Everyday sayings, then nods to games and films (short lines only; a reply only where the original has one).
            for (String block : List.of("pairs", "references")) {
                if (!language.has(block)) {
                    continue;
                }
                for (JsonElement element : language.getAsJsonArray(block)) {
                    JsonObject pair = element.getAsJsonObject();
                    pairs.add(new Pair(pair.get("line").getAsString(), pair.has("reply") ? pair.get("reply").getAsString() : ""));
                }
            }
            List<String> heard = new ArrayList<>();
            language.getAsJsonArray("heard").forEach(line -> heard.add(line.getAsString()));
            read.put(entry.getKey(), new Language(List.copyOf(pairs), List.copyOf(heard)));
        });
        languages = Map.copyOf(read);
    }

    /**
     * A catchphrase for the colonist with {@code speakerKey} to say now, if the dice and the cooldowns allow it.
     *
     * @param language the chat's language as the mod names it ("Russian"), English when the file has none for it
     * @param friend   the name of someone the speaker is close to, or null
     * @param enemy    the name of someone the speaker can't stand, or null
     * @param now      server ticks, for the colony's cooldown
     * @param gameTime game time, for the colonist's and the phrase's cooldowns in game days
     */
    public Optional<Said> maybe(int colonyId, String speakerKey, String language, String friend, String enemy, long now,
                                long gameTime, Random random) {
        Language phrases = languages.getOrDefault(language, languages.get("English"));
        if (phrases == null || random.nextDouble() >= chance
                || now - lastInColony.getOrDefault(colonyId, Long.MIN_VALUE / 2) < colonyCooldown
                || gameTime - lastByColonist.getOrDefault(speakerKey, Long.MIN_VALUE / 2) < colonistCooldown) {
            return Optional.empty();
        }
        List<Pair> fitting = phrases.pairs().stream()
                .filter(pair -> (!pair.line().contains("{enemy}") || enemy != null) && (!pair.line().contains("{friend}") || friend != null))
                .filter(pair -> gameTime - lastOfPhrase.getOrDefault(colonyId + ":" + pair.line(), Long.MIN_VALUE / 2) >= phraseCooldown)
                .toList();
        if (fitting.isEmpty()) {
            return Optional.empty();
        }
        Pair pair = fitting.get(random.nextInt(fitting.size()));
        lastInColony.put(colonyId, now);
        lastByColonist.put(speakerKey, gameTime);
        lastOfPhrase.put(colonyId + ":" + pair.line(), gameTime);
        String replier = pair.line().contains("{enemy}") ? enemy : pair.line().contains("{friend}") ? friend : null;
        String line = pair.line().replace("{enemy}", enemy == null ? "" : enemy).replace("{friend}", friend == null ? "" : friend);
        return Optional.of(new Said(line, pair.reply(), replier));
    }

    /** How the phrase is answered: its reply, "heard that one before", or nothing. */
    public static Optional<String> answer(Said said, String language, Random random) {
        Language phrases = languages.getOrDefault(language, languages.get("English"));
        double roll = random.nextDouble();
        if (roll < replyChance && !said.reply().isEmpty()) {
            return Optional.of(said.reply());
        }
        if (phrases != null && !phrases.heard().isEmpty() && roll < replyChance + heardChance) {
            return Optional.of(phrases.heard().get(random.nextInt(phrases.heard().size())));
        }
        return Optional.empty();
    }
}
