package me.bytefox.livingcolonists.friendship;

/**
 * How a colony's calendar goes (#67): a calendar day passes on a game day when colony members were inside the colony
 * long enough, never more than one a game day, and not at all while nobody is around. Saved with the world.
 */
public final class Presence {
    /** The colony's calendar day, -1 before the first. */
    private int day = -1;
    /** The game day the calendar day last passed on. */
    private long countedGameDay = -1;
    private long nearGameDay = -1;
    private long nearTicks;

    public Presence() {
    }

    Presence(int day, long countedGameDay, long nearGameDay, long nearTicks) {
        this.day = day;
        this.countedGameDay = countedGameDay;
        this.nearGameDay = nearGameDay;
        this.nearTicks = nearTicks;
    }

    /**
     * Members were inside the colony for {@code ticks} more on game day {@code gameDay}. Whether a calendar day passed
     * just now: once {@code need} ticks have come together over that game day.
     */
    public boolean near(long gameDay, long ticks, long need) {
        if (gameDay != nearGameDay) {
            nearGameDay = gameDay;
            nearTicks = 0;
        }
        nearTicks += ticks;
        if (countedGameDay == gameDay || nearTicks < need) {
            return false;
        }
        countedGameDay = gameDay;
        day++;
        return true;
    }

    /** Whether the calendar day passed on this game day: today is that calendar day. */
    public boolean isToday(long gameDay) {
        return day >= 0 && countedGameDay == gameDay;
    }

    public int day() {
        return day;
    }

    /** For /lcdebug: the calendar jumps to {@code day}, as today. */
    void set(int day, long gameDay) {
        this.day = day;
        countedGameDay = gameDay;
    }

    long countedGameDay() {
        return countedGameDay;
    }

    long nearGameDay() {
        return nearGameDay;
    }

    long nearTicks() {
        return nearTicks;
    }
}
