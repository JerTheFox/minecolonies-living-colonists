package me.bytefox.livingcolonists.behaviour;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A per-player pause between messages to colonists, so chat spam can't burn through tokens. */
public final class Cooldown {
    private final Map<UUID, Long> lastUse = new HashMap<>();

    /**
     * @return empty if the player may send now (and the use is recorded), otherwise the ticks left
     */
    public Optional<Long> tryUse(UUID player, long now, long cooldownTicks) {
        Long last = lastUse.get(player);
        if (last != null && now - last < cooldownTicks) {
            return Optional.of(cooldownTicks - (now - last));
        }
        lastUse.put(player, now);
        return Optional.empty();
    }

    public void forget(UUID player) {
        lastUse.remove(player);
    }

    public void clear() {
        lastUse.clear();
    }
}
