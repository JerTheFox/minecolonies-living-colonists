package me.bytefox.livingcolonists.friendship;

/**
 * How friendship shows in a colonist's work (#67): the share of the configured bonus (walking speed, experience in their
 * main skill) by the friendship level of the average rapport of the players who know them. Friends help from the first
 * level on; only real enmity (at odds, a grudge) slows them down.
 */
public final class WorkBonus {
    private WorkBonus() {
    }

    /** From -1 (a grudge: the full penalty) to 1 (a best friend: the full bonus). */
    public static double fraction(int rapport) {
        return switch (FriendshipLevels.level(rapport).id()) {
            case "friend" -> 0.25;
            case "good" -> 0.5;
            case "close" -> 0.75;
            case "best" -> 1;
            case "odds" -> -0.5;
            case "enemy" -> -1;
            default -> 0;
        };
    }
}
