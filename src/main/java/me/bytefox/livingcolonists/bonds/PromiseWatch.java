/*
 * Based on PromiseWatcher from Colonist Errands by Lovkar, licensed GPL-3.0-or-later
 * (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3, src/me/lovkar/errands/PromiseWatcher.java).
 * Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.memory.PlayerMarks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Notices on its own when a promise came true (the colonist got a home, a job or got well, the promised building
 * was finished, the promised request was delivered) or lapsed, and has the colonist remind the maker of an overdue
 * one. Server thread only.
 */
final class PromiseWatch {
    /** How often promises are checked, in server ticks (ten seconds). */
    static final int CHECK_INTERVAL = 200;
    /** At most one promise reminder per player in this many server ticks (ten minutes). */
    static final long REMINDER_GAP_TICKS = 12000;

    private final BondsService bonds;
    /** The colony events already seen, per colony; events from before the server started count as seen. */
    private final Map<Integer, Set<IColonyEventDescription>> seenEvents = new HashMap<>();
    /** Who reminds a player of an overdue promise; set by the talk layer. */
    private BondsService.Reminder reminder;
    /** Server tick of each player's last reminder, so reminders don't pile up. */
    private final Map<UUID, Long> lastReminder = new HashMap<>();

    PromiseWatch(BondsService bonds) {
        this.bonds = bonds;
    }

    void setReminder(BondsService.Reminder reminder) {
        this.reminder = reminder;
    }

    void clear() {
        seenEvents.clear();
        lastReminder.clear();
    }

    /** Notices promises that came true or lapsed. Server tick. */
    void tick(MinecraftServer server) {
        if (bonds.data() == null) {
            return;
        }
        if (server.getTickCount() % PromiseDialogs.REQUEST_CHECK_INTERVAL == 0) {
            bonds.dialogs().watchRequests(server);
        }
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getWorld() == null) {
                continue;
            }
            try {
                watchBuildings(server, colony);
                watchNeeds(server, colony);
            } catch (RuntimeException e) {
                ModLog.warn("Could not check promises in colony {}", colony.getName(), e);
            }
        }
    }

    /** New "built" and "upgraded" events of the colony are checked against open promises. */
    private void watchBuildings(MinecraftServer server, IColony colony) {
        List<IColonyEventDescription> events = colony.getEventDescriptionManager().getEventDescriptions();
        Set<IColonyEventDescription> seen = seenEvents.get(colony.getID());
        Set<IColonyEventDescription> now = Collections.newSetFromMap(new IdentityHashMap<>());
        now.addAll(events);
        seenEvents.put(colony.getID(), now);
        if (seen == null) {
            return;
        }
        for (IColonyEventDescription event : events) {
            if (seen.contains(event) || !(event instanceof IBuildingEventDescription building)) {
                continue;
            }
            String type = event.getEventTypeId().getPath();
            if (!type.contains("built") && !type.contains("upgraded")) {
                continue;
            }
            String name = Component.translatable(building.getBuildingName()).getString();
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                for (Promise promise : bonds.promises(citizen)) {
                    if (promise.isOpen() && Bonds.mentionsBuilding(promise.text(), name)) {
                        keptByItself(server, citizen, promise, "the " + name + " is finished");
                    }
                }
            }
        }
    }

    /** Home, job and health promises close when the need, once seen unmet, is met; others lapse. */
    private void watchNeeds(MinecraftServer server, IColony colony) {
        long now = BondsService.now(colony);
        String prefix = colony.getID() + ":";
        for (Map.Entry<String, List<Promise>> entry : List.copyOf(bonds.data().promises.entrySet())) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            ICitizenData citizen = colony.getCitizenManager().getCivilian(Integer.parseInt(entry.getKey().split(":")[1]));
            if (citizen == null) {
                continue;
            }
            for (Promise promise : List.copyOf(entry.getValue())) {
                if (!promise.isOpen()) {
                    continue;
                }
                var lapse = Bonds.expiry(promise, now);
                if (lapse.isPresent()) {
                    lapsed(server, citizen, promise, lapse.get(), now);
                    continue;
                }
                if (promise.due() > 0 && now > promise.due() && !promise.reminded()) {
                    remindOnce(server, citizen, promise);
                }
                if (promise.about() != Promise.About.HOUSING && promise.about() != Promise.About.WORK
                        && promise.about() != Promise.About.HEALTH) {
                    continue;
                }
                if (BondsService.needUnmet(citizen, promise.about())) {
                    if (!promise.needSeen()) {
                        bonds.replace(citizen, promise.withNeedSeen());
                    }
                } else if (promise.needSeen()) {
                    keptByItself(server, citizen, promise, switch (promise.about()) {
                        case HOUSING -> "they have a home now";
                        case WORK -> "they have a job now";
                        default -> "they are well again";
                    });
                }
            }
        }
    }

    /** The colonist reminds the maker of an overdue promise in private, once, if the maker is around. */
    private void remindOnce(MinecraftServer server, ICitizenData citizen, Promise promise) {
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        long tick = server.getTickCount();
        if (maker == null || reminder == null || tick - lastReminder.getOrDefault(maker.getUUID(), Long.MIN_VALUE / 2)
                < REMINDER_GAP_TICKS) {
            return;
        }
        if (reminder.remind(maker, citizen, promise)) {
            lastReminder.put(maker.getUUID(), tick);
            bonds.replace(citizen, promise.withReminded());
        }
    }

    void keptByItself(MinecraftServer server, ICitizenData citizen, Promise promise, String how) {
        bonds.resolve(citizen, promise.id(), Promise.Status.KEPT, BondsService.now(citizen.getColony()));
        ModLog.action("{} noticed a promise came true ({}): {}", citizen.getName(), how,
                PlayerMarks.resolve(promise.text()));
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null) {
            maker.displayClientMessage(Component.translatable("livingcolonists.promise.kept", citizen.getName()), true);
        }
    }

    private void lapsed(MinecraftServer server, ICitizenData citizen, Promise promise, Promise.Status status, long now) {
        bonds.resolve(citizen, promise.id(), status, now);
        ModLog.action("A promise to {} lapsed as {}: {}", citizen.getName(), status,
                PlayerMarks.resolve(promise.text()));
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null && status == Promise.Status.BROKEN) {
            maker.displayClientMessage(Component.translatable("livingcolonists.promise.broken", citizen.getName()), true);
        }
    }

}
