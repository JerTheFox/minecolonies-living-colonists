package me.bytefox.livingcolonists.friendship;

/** A day of the colony calendar (#67): {@code day} counts the colony's days from 0, the first day of spring, year 1. */
public record CalendarDate(int day) {
    public static final String[] SEASONS = {"spring", "summer", "autumn", "winter"};

    public int year() {
        return day / CalendarTable.yearDays() + 1;
    }

    public int dayOfYear() {
        return day % CalendarTable.yearDays();
    }

    /** 0 spring, 1 summer, 2 autumn, 3 winter. */
    public int season() {
        return dayOfYear() / CalendarTable.seasonDays();
    }

    /** From 1. */
    public int dayOfSeason() {
        return dayOfYear() % CalendarTable.seasonDays() + 1;
    }

    /** "summer, day 4 of year 1": how the model is told the date. */
    public String english() {
        return SEASONS[season()] + ", day " + dayOfSeason() + " of year " + year();
    }
}
