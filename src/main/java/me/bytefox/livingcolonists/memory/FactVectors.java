package me.bytefox.livingcolonists.memory;

import me.bytefox.livingcolonists.bonds.PlayerMarks;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.llm.EmbeddingClient;
import me.bytefox.livingcolonists.llm.LlmEndpoint;
import net.minecraft.server.MinecraftServer;

/**
 * Vectors of remembered facts, for finding them by meaning: asked for in the background, kept in memory only
 * (rebuilt after a restart, never saved with the world), dropped when the embeddings model changes. Server
 * thread only.
 */
final class FactVectors {
    private static final Duration FACTS_WAIT = Duration.ofSeconds(20);
    /** Vectors kept in memory, by fact text; rebuilt after a restart, never saved with the world. */
    private static final int MAX_VECTORS = 20000;

    private final EmbeddingClient embeddings;
    private final Map<String, float[]> vectors = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_VECTORS;
        }
    };
    /** Fact texts whose vectors are on their way. */
    private final Set<String> embedding = new HashSet<>();
    /** The model the vectors were made with: another model's vectors don't compare. */
    private String vectorModel = "";
    /** Bumped when the world's memory changes: vectors asked for before then are not kept. */
    private long generation;

    FactVectors(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    /** The vector of a fact's text, or null if there is none yet. */
    float[] get(String text) {
        return vectors.get(text);
    }

    void clear() {
        vectors.clear();
        embedding.clear();
        generation++;
    }

    /** Asks for the vectors of facts that have none yet, in the background; they are used once they come. */
    void embed(Collection<Fact> facts, LlmEndpoint endpoint, MinecraftServer server) {
        List<String> missing = facts.stream().map(Fact::text)
                .filter(text -> !vectors.containsKey(text) && !embedding.contains(text)).distinct().toList();
        if (missing.isEmpty()) {
            return;
        }
        embedding.addAll(missing);
        long asked = generation;
        String model = endpoint.model();
        embeddings.embed(endpoint, missing.stream().map(PlayerMarks::resolve).toList(), FACTS_WAIT)
                .whenComplete((result, error) -> server.execute(() -> {
                    missing.forEach(embedding::remove);
                    if (error != null) {
                        ModLog.warn("Could not get embeddings for {} facts: {}", missing.size(),
                                error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                        return;
                    }
                    if (generation == asked && model.equals(vectorModel)) {
                        for (int i = 0; i < missing.size(); i++) {
                            vectors.put(missing.get(i), result.get(i));
                        }
                    }
                }));
    }

    /** Vectors of another model don't compare with the new one's: they are made again. */
    void useModel(LlmEndpoint endpoint) {
        if (!endpoint.model().equals(vectorModel)) {
            vectors.clear();
            vectorModel = endpoint.model();
        }
    }

}
