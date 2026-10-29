package me.bytefox.livingcolonists.personality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.prompt.PromptLibrary;

/**
 * Archetypes from {@code config/livingcolonists/archetypes/*.json}. The bundled set is written out
 * only when the folder doesn't exist yet, so deleting an archetype file keeps it deleted; deleting
 * the whole folder brings the defaults back.
 */
public final class ArchetypeLibrary {
    /** The archetypes bundled with the mod. */
    static final List<String> BUNDLED = List.of("chatterbox", "dreamer", "gossip", "grumbler", "idler", "joker",
            "optimist", "quiet", "thinker", "workhorse", "worrier");

    private final Path directory;
    private volatile List<Archetype> archetypes = List.of();

    public ArchetypeLibrary(Path configDirectory) {
        this.directory = configDirectory.resolve("livingcolonists").resolve("archetypes");
    }

    public void reload() {
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
                for (String id : BUNDLED) {
                    Files.writeString(directory.resolve(id + ".json"), PromptLibrary.bundled("archetypes/" + id + ".json"),
                            StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            LivingColonists.LOGGER.warn("Could not write the default archetypes to {}: {}", directory, e.toString());
        }
        List<Archetype> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String id = file.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                    loaded.add(Archetypes.parse(id, Files.readString(file, StandardCharsets.UTF_8)));
                } catch (RuntimeException | IOException e) {
                    // A value of the wrong JSON type (an object for a number) throws more than IllegalArgumentException.
                    LivingColonists.LOGGER.warn("Skipping archetype {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            LivingColonists.LOGGER.warn("Could not read archetypes from {}: {}", directory, e.toString());
        }
        archetypes = List.copyOf(loaded);
    }

    public List<Archetype> all() {
        return archetypes;
    }

    /** The archetype with this id, or the ordinary colonist if it was deleted meanwhile. */
    public Archetype get(String id) {
        return find(id).orElse(Archetype.ORDINARY);
    }

    public Optional<Archetype> find(String id) {
        return archetypes.stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
