package me.bytefox.livingcolonists.memory;

import java.util.List;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Module;

/**
 * Short memory: what a colonist and a player said to each other in private, and the colony chat.
 * How much of a private talk is kept follows the memory level; the colony chat always keeps a few
 * recent lines, because a conversation thread can't go on without them. Server thread only.
 */
public final class MemoryService {
    /** Colony chat lines kept for conversation threads. */
    private static final int COLONY_LINES = 16;
    /** Colony chat lines older than this (one Minecraft day) are dropped. */
    private static final long COLONY_MAX_AGE = 24000;

    private MemoryData data;

    public void attach(MemoryData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
    }

    public List<MemoryLine> privateTalk(ICitizenData citizen, UUID player) {
        MemoryLimits limits = MemoryLimits.of(Config.level(Module.MEMORY));
        Transcript transcript = data == null || limits.lines() == 0 ? null : data.privateTalks.get(key(citizen, player));
        return transcript == null ? List.of() : transcript.lines(now(citizen.getColony()), limits.maxAgeTicks());
    }

    public void rememberPrivate(ICitizenData citizen, UUID player, String speaker, String text) {
        MemoryLimits limits = MemoryLimits.of(Config.level(Module.MEMORY));
        if (data == null || limits.lines() == 0) {
            return;
        }
        data.privateTalks.computeIfAbsent(key(citizen, player), k -> new Transcript())
                .add(new MemoryLine(now(citizen.getColony()), speaker, text), limits.lines(), limits.maxAgeTicks());
        data.setDirty();
    }

    public List<MemoryLine> colonyChat(IColony colony) {
        Transcript transcript = data == null ? null : data.colonyChats.get(colony.getID());
        return transcript == null ? List.of() : transcript.lines(now(colony), COLONY_MAX_AGE);
    }

    public void rememberColony(IColony colony, String speaker, String text) {
        if (data == null) {
            return;
        }
        data.colonyChats.computeIfAbsent(colony.getID(), k -> new Transcript())
                .add(new MemoryLine(now(colony), speaker, text), COLONY_LINES, COLONY_MAX_AGE);
        data.setDirty();
    }

    /** Forgets everything the colonist remembers of private talks; returns how many talks were dropped. */
    public int forget(ICitizenData citizen) {
        if (data == null) {
            return 0;
        }
        String prefix = citizen.getColony().getID() + ":" + citizen.getId() + ":";
        int before = data.privateTalks.size();
        data.privateTalks.keySet().removeIf(key -> key.startsWith(prefix));
        data.setDirty();
        return before - data.privateTalks.size();
    }

    /** All private talks of the colonist, by player UUID, for {@code /lcdebug memory}. */
    public java.util.Map<String, List<MemoryLine>> privateTalks(ICitizenData citizen) {
        java.util.Map<String, List<MemoryLine>> talks = new java.util.TreeMap<>();
        if (data == null) {
            return talks;
        }
        String prefix = citizen.getColony().getID() + ":" + citizen.getId() + ":";
        data.privateTalks.forEach((key, transcript) -> {
            if (key.startsWith(prefix)) {
                talks.put(key.substring(prefix.length()), transcript.all());
            }
        });
        return talks;
    }

    private static String key(ICitizenData citizen, UUID player) {
        return citizen.getColony().getID() + ":" + citizen.getId() + ":" + player;
    }

    private static long now(IColony colony) {
        return colony.getWorld().getGameTime();
    }
}
