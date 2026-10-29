package me.bytefox.livingcolonists;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;

/**
 * Colonists as the mod's saved data refers to them: keys starting with {@code colonyId:citizenId}.
 *
 * <p>MineColonies gives a new colonist the lowest free citizen id, so the id of one who died soon
 * belongs to somebody else. Data of a gone colonist has to be dropped before that, or the newcomer
 * inherits their character, memories and promises.</p>
 */
public final class Citizens {
    private Citizens() {
    }

    /** A loaded colony by id; keys don't carry the dimension, so the first match wins. */
    public static IColony colony(int id) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getID() == id && colony.getWorld() != null) {
                return colony;
            }
        }
        return null;
    }

    /**
     * Whether the key's colonist no longer lives in a loaded colony. A colony that isn't loaded
     * counts as still there, so nothing is dropped by mistake.
     */
    public static boolean isGone(String key) {
        String[] parts = key.split(":");
        try {
            IColony colony = colony(Integer.parseInt(parts[0]));
            return colony != null && colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1])) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
