package me.bytefox.livingcolonists.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.bytefox.livingcolonists.LivingColonists;

/**
 * Prompt templates from {@code config/livingcolonists/prompts/}. A missing file is restored from
 * the copy bundled in the mod, so deleting one resets it to the default.
 */
public final class PromptLibrary {
    public static final String REPLY_PRIVATE = "reply-private.txt";
    public static final String REPLY_COLONY = "reply-colony.txt";
    public static final String CHARACTER = "character.txt";
    public static final String BACKGROUND = "background.txt";
    public static final String ROUTE_COLONY = "route-colony.txt";
    public static final String BIO = "bio.txt";
    public static final String BIO_CHILD = "bio-child.txt";
    public static final String BIO_GROWNUP = "bio-grownup.txt";

    public static final List<String> ALL = List.of(REPLY_PRIVATE, REPLY_COLONY, CHARACTER, BACKGROUND, ROUTE_COLONY,
            BIO, BIO_CHILD, BIO_GROWNUP);

    private final Path directory;
    private volatile Map<String, String> templates = Map.of();

    public PromptLibrary(Path configDirectory) {
        this.directory = configDirectory.resolve("livingcolonists").resolve("prompts");
    }

    /** Reads every template, writing out the bundled default for any that is missing. */
    public void reload() {
        Map<String, String> loaded = new HashMap<>();
        for (String name : ALL) {
            Path file = directory.resolve(name);
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(directory);
                    Files.writeString(file, bundled("prompts/" + name), StandardCharsets.UTF_8);
                }
                loaded.put(name, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                LivingColonists.LOGGER.warn("Could not read prompt {}, using the default: {}", file, e.toString());
                loaded.put(name, bundled("prompts/" + name));
            }
        }
        templates = Map.copyOf(loaded);
    }

    public String get(String name) {
        String template = templates.get(name);
        return template != null ? template : bundled("prompts/" + name);
    }

    public int size() {
        return templates.size();
    }

    /** A file bundled in the mod under {@code /defaults/}. */
    public static String bundled(String path) {
        try (InputStream in = PromptLibrary.class.getResourceAsStream("/defaults/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled file /defaults/" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
