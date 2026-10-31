package me.bytefox.livingcolonists.bonds;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A promise's deadline read by code from the player's own words (#11): "in ten minutes", "через два
 * часа", "к вечеру", "tomorrow", "через неделю". Minutes and hours are real time; evenings and days
 * follow the game clock. The model's guess in days stays for wording code doesn't know.
 */
final class Deadlines {
    private static final long MINUTE = 1200;
    private static final long HOUR = 72000;
    private static final long DAY = 24000;
    /** Day time of the evening, as a deadline "by evening" means. */
    private static final long EVENING = 12000;

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("один", 1), Map.entry("одну", 1), Map.entry("одна", 1), Map.entry("два", 2), Map.entry("две", 2),
            Map.entry("пару", 2), Map.entry("три", 3), Map.entry("четыре", 4), Map.entry("пять", 5), Map.entry("шесть", 6),
            Map.entry("семь", 7), Map.entry("восемь", 8), Map.entry("девять", 9), Map.entry("десять", 10),
            Map.entry("пятнадцать", 15), Map.entry("двадцать", 20), Map.entry("тридцать", 30),
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("couple", 2),
            Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("fifteen", 15),
            Map.entry("twenty", 20), Map.entry("thirty", 30));

    private static final String NUMBER = "(\\d+|[a-zа-яё]+)?\\s*(?:of\\s+)?";
    private static final Pattern AMOUNT = Pattern.compile("(?:через|in|within)\\s+" + NUMBER
            + "(минут[уаы]?|мин|minutes?|mins?|час(?:а|ов)?|hours?|дн(?:я|ей)|день|days?|недел[юи]|weeks?)");
    private static final Pattern HALF_HOUR = Pattern.compile("полчаса|half an hour");
    private static final Pattern EVENING_WORDS = Pattern.compile("к вечеру|вечером|сегодня|tonight|today|by evening|this evening");
    private static final Pattern TOMORROW = Pattern.compile("завтра|tomorrow");

    private Deadlines() {
    }

    /** Game time the latest line naming a time sets as the deadline; empty if none names one. */
    static OptionalLong due(List<String> lines, long gameTime, long dayTime) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            OptionalLong due = of(lines.get(i).toLowerCase(Locale.ROOT), gameTime, Math.floorMod(dayTime, DAY));
            if (due.isPresent()) {
                return due;
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong of(String line, long now, long dayTime) {
        Matcher amount = AMOUNT.matcher(line);
        if (amount.find()) {
            String unit = amount.group(2);
            int count = amount.group(1) == null ? 1 : count(amount.group(1));
            if (count > 0) {
                long step = unit.startsWith("мин") || unit.startsWith("min") ? MINUTE
                        : unit.startsWith("час") || unit.startsWith("hour") ? HOUR
                        : unit.startsWith("нед") || unit.startsWith("week") ? 7 * DAY : DAY;
                return OptionalLong.of(now + count * step);
            }
        }
        if (HALF_HOUR.matcher(line).find()) {
            return OptionalLong.of(now + HOUR / 2);
        }
        long startOfDay = now - dayTime;
        if (TOMORROW.matcher(line).find()) {
            return OptionalLong.of(startOfDay + DAY + EVENING);
        }
        if (EVENING_WORDS.matcher(line).find()) {
            return OptionalLong.of(dayTime < EVENING ? startOfDay + EVENING : startOfDay + DAY + EVENING);
        }
        return OptionalLong.empty();
    }

    private static int count(String word) {
        if (word.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(word);
        }
        return NUMBERS.getOrDefault(word, 0);
    }
}
