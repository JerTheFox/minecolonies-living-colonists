package me.bytefox.livingcolonists.mood;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Every colony's mood (#65), by colony id. Time is the server's tick count. Server thread only. */
public final class ColonyMoods {
    private final Map<Integer, ColonyMood> colonies = new HashMap<>();

    public void raise(int colonyId, String occasion, String cause, Collection<Integer> close, long now) {
        colonies.computeIfAbsent(colonyId, id -> new ColonyMood()).raise(occasion, cause, Set.copyOf(close), now);
    }

    /** How the model is told of the colony's mood for this colonist; empty while it is calm. */
    public Optional<String> tone(int colonyId, int citizenId, long now) {
        ColonyMood mood = colonies.get(colonyId);
        return mood == null ? Optional.empty() : mood.tone(now, citizenId);
    }

    public ColonyMood.Leading leading(int colonyId, long now) {
        ColonyMood mood = colonies.get(colonyId);
        return mood == null ? new ColonyMood.Leading(Mood.CALM, 0) : mood.leading(now);
    }

    public List<String> describe(int colonyId, long now) {
        ColonyMood mood = colonies.get(colonyId);
        return mood == null ? List.of() : mood.describe(now);
    }

    /** A deleted colony's id may go to a new one. */
    public void forget(int colonyId) {
        colonies.remove(colonyId);
    }
}
