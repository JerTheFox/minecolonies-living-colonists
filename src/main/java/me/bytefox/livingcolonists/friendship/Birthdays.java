package me.bytefox.livingcolonists.friendship;

import java.util.Collection;

/** Birthdays of colonists who came from elsewhere (#67): spread over the year, two on one day only once all are taken. */
public final class Birthdays {
    private Birthdays() {
    }

    /** A day of the year for {@code citizenKey}, the same each time for the same days taken. */
    public static int pick(Collection<Integer> taken, String citizenKey, int yearDays) {
        int start = Math.floorMod(citizenKey.hashCode() * 31 + 17, yearDays);
        for (int step = 0; step < yearDays; step++) {
            int day = Math.floorMod(start + step * 37, yearDays);
            if (!taken.contains(day)) {
                return day;
            }
        }
        return start;
    }
}
