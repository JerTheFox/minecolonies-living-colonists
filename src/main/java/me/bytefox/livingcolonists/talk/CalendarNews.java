package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.errands.GuardTally;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.friendship.Calendar;
import me.bytefox.livingcolonists.friendship.Holiday;
import me.bytefox.livingcolonists.personality.PersonalityService;
import net.minecraft.server.MinecraftServer;

/**
 * Tells the colony of a new calendar day (#67): a holiday, with code's part of it (the fair's best worker, the
 * tournament's favourite, the departed, the year's tally), and birthdays. The calendar itself knows nothing of talk.
 */
final class CalendarNews {
    /** At most this many departed are named on the Night of Remembrance. */
    private static final int DEPARTED_NAMED = 5;

    private final ColonyEvents events;
    private final GuardTally tally;
    private final Calendar calendar;

    CalendarNews(ColonyEvents events, GuardTally tally, Calendar calendar) {
        this.events = events;
        this.tally = tally;
        this.calendar = calendar;
    }

    void newDay(GameEvents.CalendarDay day) {
        IColony colony = day.colony();
        MinecraftServer server = colony.getWorld() == null ? null : colony.getWorld().getServer();
        if (server == null) {
            return;
        }
        day.holiday().ifPresent(holiday -> {
            List<Integer> speakers = new ArrayList<>();
            String what = holiday.text() + special(colony, holiday, speakers);
            events.announce(server, colony, what, speakers, holiday.occasion());
        });
        for (ICitizenData citizen : day.birthdays()) {
            events.announce(server, colony, "Today is " + citizen.getName() + "'s birthday.", List.of(citizen.getId()), "birthday");
        }
    }

    private String special(IColony colony, Holiday holiday, List<Integer> speakers) {
        List<ICitizenData> adults = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).toList();
        switch (holiday.special()) {
            case "fair" -> {
                ICitizenData best = null;
                int top = -1;
                for (ICitizenData citizen : adults) {
                    int level = PersonalityService.skills(citizen).values().stream().mapToInt(Integer::intValue).max().orElse(0);
                    if (level > top) {
                        top = level;
                        best = citizen;
                    }
                }
                if (best != null) {
                    speakers.add(best.getId());
                    return " Most agree that " + best.getName() + " is the best at their work this season.";
                }
            }
            case "tournament" -> {
                speakers.addAll(ColonyEvents.guards(colony));
                return tally.best(colony).map(best -> " The favourite is " + best.replaceFirst(": (\\d+)$", ", with $1 enemies beaten") + ".")
                        .orElse(" No guard has beaten an enemy yet, so it's all bluster.");
            }
            case "remembrance" -> {
                List<String> departed = calendar.departed(colony.getID());
                List<String> named = departed.subList(Math.max(0, departed.size() - DEPARTED_NAMED), departed.size());
                return " They remember " + String.join(", ", named) + ".";
            }
            case "year_end" -> {
                int[] year = calendar.thisYear(colony.getID());
                return " This year " + year[0] + " were born and " + year[1] + " died; the colony has " + adults.size()
                        + " grown colonists and " + colony.getServerBuildingManager().getBuildings().size() + " buildings.";
            }
            default -> {
            }
        }
        return "";
    }

    /** For a colonist's line: the date by the colony calendar, their birthday, and a holiday or birthdays today. */
    static Optional<String> situation(Calendar calendar, ICitizenData citizen) {
        List<String> lines = new ArrayList<>();
        calendar.date(citizen.getColony()).ifPresent(date -> lines.add("By the colony calendar it is " + date.english() + "."));
        calendar.birthday(citizen).ifPresent(day -> lines.add("Your birthday is on "
                + new me.bytefox.livingcolonists.friendship.CalendarDate(day).english().replaceFirst(" of year \\d+", "") + "."));
        calendar.holiday(citizen.getColony()).ifPresent(holiday -> lines.add(holiday.text()));
        if (calendar.isBirthday(citizen)) {
            lines.add("Today is your birthday.");
        }
        for (ICitizenData other : citizen.getColony().getCitizenManager().getCitizens()) {
            if (other.getId() != citizen.getId() && calendar.isBirthday(other)) {
                lines.add("Today is " + other.getName() + "'s birthday.");
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", lines));
    }
}
