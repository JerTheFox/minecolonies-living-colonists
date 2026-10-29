package me.bytefox.livingcolonists.behaviour;

/**
 * Who may talk to a colony's colonists and who may give them orders, by the player's rank in the
 * colony. Spending the server's tokens and ordering colonists around are for the colony's people.
 */
public final class ColonyAccess {
    public enum Role {
        OWNER,
        OFFICER,
        FRIEND,
        /** Neutral, hostile, a custom rank or no rank at all. */
        OTHER
    }

    private ColonyAccess() {
    }

    /** @param membersOnly the config switch; off lets anyone inside the colony talk */
    public static boolean canTalk(Role role, boolean membersOnly) {
        return !membersOnly || role != Role.OTHER;
    }

    /** Friends may chat, but colonists politely turn down their orders. */
    public static boolean canGiveOrders(Role role, boolean membersOnly) {
        return !membersOnly || role == Role.OWNER || role == Role.OFFICER;
    }
}
