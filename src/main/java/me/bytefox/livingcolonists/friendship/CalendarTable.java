package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The calendar's settings and holidays (#67), from {@code config/livingcolonists/content/calendar.json}. */
public final class CalendarTable {
    private static volatile int seasonDays = 24;
    private static volatile long nearTicks = 5 * 60 * 20;
    private static volatile int birthdayGift = 2;
    private static volatile List<Holiday> holidays = List.of();

    private CalendarTable() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Holiday> read = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("holidays")) {
            JsonObject h = element.getAsJsonObject();
            List<String> favours = h.has("favours")
                    ? h.getAsJsonArray("favours").asList().stream().map(JsonElement::getAsString).toList() : List.of();
            read.add(new Holiday(h.get("id").getAsString(), h.get("name").getAsString(), h.get("season").getAsInt(),
                    h.get("day").getAsInt(), h.has("fromYear") ? h.get("fromYear").getAsInt() : 1, h.get("occasion").getAsString(),
                    h.get("text").getAsString(), favours, h.has("special") ? h.get("special").getAsString() : ""));
        }
        seasonDays = Math.max(1, root.get("seasonDays").getAsInt());
        nearTicks = Math.round(root.get("nearMinutes").getAsDouble() * 60 * 20);
        birthdayGift = root.has("birthdayGift") ? root.get("birthdayGift").getAsInt() : 2;
        holidays = List.copyOf(read);
    }

    public static int seasonDays() {
        return seasonDays;
    }

    public static int yearDays() {
        return 4 * seasonDays;
    }

    /** How long members must be inside the colony over a game day for a calendar day to pass, in ticks. */
    public static long nearTicks() {
        return nearTicks;
    }

    /** How many times a birthday gift counts. */
    public static int birthdayGift() {
        return birthdayGift;
    }

    public static Optional<Holiday> holiday(CalendarDate date) {
        return holidays.stream().filter(h -> h.season() == date.season() && h.day() == date.dayOfSeason()
                && date.year() >= h.fromYear()).findFirst();
    }

    public static List<Holiday> holidays() {
        return holidays;
    }
}
