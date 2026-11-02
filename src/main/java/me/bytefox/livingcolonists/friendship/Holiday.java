package me.bytefox.livingcolonists.friendship;

import java.util.List;

/**
 * A colony holiday (#67), from {@code content/calendar.json}.
 *
 * @param season   0 spring, 1 summer, 2 autumn, 3 winter
 * @param day      day of the season, from 1
 * @param fromYear the first year it is kept (Founding Day isn't on the colony's very first day)
 * @param occasion the row of the mood table it stirs
 * @param text     what the colony is told, for the model
 * @param favours  gifts that count as loved that day
 * @param special  code's part of the day: fair, tournament, remembrance, gift_exchange, year_end, or ""
 */
public record Holiday(String id, String name, int season, int day, int fromYear, String occasion, String text,
                      List<String> favours, String special) {
}
