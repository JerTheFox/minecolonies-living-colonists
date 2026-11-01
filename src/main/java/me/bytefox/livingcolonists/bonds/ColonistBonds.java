package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.memory.Digests;

/**
 * How colonists get along among themselves: what the character model wrote down from colony talks, and the daily
 * drift from living and working together (#59). Kept in {@link BondsData#colonists} by pair. Server thread only.
 */
public final class ColonistBonds {
    /** A nickname-like part of a colonist's name the character model may use instead of the full name. */
    private static final int MIN_FIRST_NAME = 3;

    private final Supplier<BondsData> store;

    ColonistBonds(Supplier<BondsData> store) {
        this.store = store;
    }

    /** How colonists got along in a colony conversation, as the character model wrote it down. */
    public void noteColonists(IColony colony, List<ICitizenData> participants, List<Digests.ColonistBond> bonds) {
        if (store.get() == null || bonds.isEmpty()) {
            return;
        }
        long now = colony.getWorld().getGameTime();
        for (Digests.ColonistBond bond : bonds) {
            ICitizenData first = byName(participants, bond.first());
            ICitizenData second = byName(participants, bond.second());
            if (first == null || second == null || first.getId() == second.getId()) {
                continue;
            }
            int delta = bond.friendly() ? Bonds.KIND : Bonds.RUDE;
            String note = Digests.cleanFact(bond.note());
            store.get().colonists.merge(pair(colony.getID(), first.getId(), second.getId()), Relation.EMPTY.with(delta, note, now),
                    (old, fresh) -> old.with(delta, note, now));
        }
        store.get().setDirty();
    }

    /**
     * Moves two colonists' rapport by {@code delta} without a note (#59); the rapport before and after. A note
     * is written only when the change means something, by the caller.
     */
    public int[] nudge(IColony colony, int first, int second, int delta) {
        if (store.get() == null || delta == 0) {
            int now = rapport(colony, first, second);
            return new int[] {now, now};
        }
        String key = pair(colony.getID(), first, second);
        Relation old = store.get().colonists.getOrDefault(key, Relation.EMPTY);
        Relation next = new Relation(Math.max(-100, Math.min(100, old.rapport() + delta)), old.notes());
        store.get().colonists.put(key, next);
        store.get().setDirty();
        return new int[] {old.rapport(), next.rapport()};
    }

    /** -100 to 100: how two colonists of the colony get along, 0 without history. */
    public int rapport(IColony colony, int first, int second) {
        Relation relation = store.get() == null ? null : store.get().colonists.get(pair(colony.getID(), first, second));
        return relation == null ? 0 : relation.rapport();
    }

    /** How the colonist gets along with the others named, for the colony chat prompt; empty if with nobody special. */
    public String colonistBlock(ICitizenData citizen, Collection<ICitizenData> others) {
        if (store.get() == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ICitizenData other : others) {
            if (other.getId() == citizen.getId()) {
                continue;
            }
            Relation relation = store.get().colonists.get(pair(citizen.getColony().getID(), citizen.getId(), other.getId()));
            String line = relation == null ? "" : Bonds.colonistLine(other.getName(), relation);
            if (!line.isEmpty()) {
                lines.add("- " + line);
            }
        }
        return lines.isEmpty() ? "" : "How you get along with people in this chat:\n" + String.join("\n", lines);
    }

    /** How the colonist gets along with each colonist they have a history with, by name, for {@code /lcdebug}. */
    public Map<String, Relation> colonistRelations(ICitizenData citizen) {
        Map<String, Relation> out = new java.util.TreeMap<>();
        if (store.get() == null) {
            return out;
        }
        IColony colony = citizen.getColony();
        String prefix = colony.getID() + ":";
        store.get().colonists.forEach((key, relation) -> {
            String[] parts = key.split(":");
            if (!key.startsWith(prefix)) {
                return;
            }
            int first = Integer.parseInt(parts[1]);
            int second = Integer.parseInt(parts[2]);
            int other = first == citizen.getId() ? second : second == citizen.getId() ? first : -1;
            ICitizenData named = other < 0 ? null : colony.getCitizenManager().getCivilian(other);
            if (named != null) {
                out.put(named.getName(), relation);
            }
        });
        return out;
    }

    /** Every pair of colonists of the colony with a history, as "A + B", for {@code /lcdebug}. */
    public Map<String, Relation> colonyRelations(IColony colony) {
        Map<String, Relation> out = new java.util.TreeMap<>();
        if (store.get() == null) {
            return out;
        }
        String prefix = colony.getID() + ":";
        store.get().colonists.forEach((key, relation) -> {
            String[] parts = key.split(":");
            if (key.startsWith(prefix)) {
                ICitizenData first = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
                ICitizenData second = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[2]));
                if (first != null && second != null) {
                    out.put(first.getName() + " + " + second.getName(), relation);
                }
            }
        });
        return out;
    }

    /** By full name, then by first name, ignoring case. */
    static ICitizenData byName(List<ICitizenData> citizens, String name) {
        String wanted = name.strip();
        for (ICitizenData citizen : citizens) {
            if (citizen.getName().equalsIgnoreCase(wanted)) {
                return citizen;
            }
        }
        for (ICitizenData citizen : citizens) {
            String first = citizen.getName().split(" ")[0];
            if (first.length() >= MIN_FIRST_NAME && first.equalsIgnoreCase(wanted)) {
                return citizen;
            }
        }
        return null;
    }

    private static String pair(int colonyId, int first, int second) {
        return colonyId + ":" + Math.min(first, second) + ":" + Math.max(first, second);
    }

}
