package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * What players do in the colony that colonists see for themselves (#50): fighting off raiders or monsters
 * inside the colony (a new hut is noticed by {@link ColonyEvents}). Told as colony news through the event
 * queue, rarely: each kind at most once in a while per player and colony. Server thread only.
 */
final class PlayerDeeds {
    /** Each kind of deed is news at most this often per player and colony, in server ticks (ten minutes). */
    private static final long SAME_DEED = 10 * 60 * 20;
    /** Kills within this window count as one fight, in server ticks (three minutes). */
    private static final long FIGHT_WINDOW = 3 * 60 * 20;
    /** Kills in one fight before it is news: raiders count at once more than monsters at night. */
    private static final int RAIDERS = 2;
    private static final int MONSTERS = 5;

    private final ColonyEvents events;
    private final Map<String, Long> told = new HashMap<>();
    private final Map<String, Deque<Long>> kills = new HashMap<>();

    PlayerDeeds(ColonyEvents events) {
        this.events = events;
    }

    void killed(ServerPlayer player, Entity victim) {
        boolean raider = victim instanceof AbstractEntityMinecoloniesRaider;
        if (!raider && !(victim instanceof Enemy)) {
            return;
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(victim.level(), victim.blockPosition());
        if (colony == null) {
            return;
        }
        long now = player.server.getTickCount();
        String kind = raider ? "raiders" : "monsters";
        Deque<Long> fight = kills.computeIfAbsent(key(player, colony, kind), k -> new ArrayDeque<>());
        fight.addLast(now);
        while (!fight.isEmpty() && now - fight.peekFirst() > FIGHT_WINDOW) {
            fight.removeFirst();
        }
        if (fight.size() < (raider ? RAIDERS : MONSTERS) || !fresh(player, colony, kind)) {
            return;
        }
        String name = TalkService.playerName(player);
        events.announce(player.server, colony, raider
                ? name + " is fighting off the raiders right in the colony: " + fight.size() + " of them are down already."
                : name + " has just cleared the monsters that crept into the colony.");
        fight.clear();
    }

    void clear() {
        told.clear();
        kills.clear();
    }

    private boolean fresh(ServerPlayer player, IColony colony, String kind) {
        long now = player.server.getTickCount();
        String key = key(player, colony, kind);
        if (now - told.getOrDefault(key, Long.MIN_VALUE / 2) < SAME_DEED) {
            return false;
        }
        told.put(key, now);
        return true;
    }

    private static String key(ServerPlayer player, IColony colony, String kind) {
        return colony.getID() + ":" + player.getUUID() + ":" + kind;
    }
}
