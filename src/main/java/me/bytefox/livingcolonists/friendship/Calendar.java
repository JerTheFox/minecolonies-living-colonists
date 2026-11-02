package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.bonds.Moods;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.events.ModEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The colony calendar (#67): a calendar day passes on a game day when colony members spent a few minutes inside the
 * colony, so holidays and birthdays come while someone is there to see them. A new day is posted as
 * {@link GameEvents.CalendarDay}; the talk code tells the colony. No model here. Server thread only.
 */
public final class Calendar {
    /** How often presence is checked, in server ticks (twenty seconds). */
    private static final int CHECK = 400;
    private static final long GAME_DAY = 24000;

    /** Something coming in the next few days, for the notebook. */
    public record Upcoming(int inDays, Holiday holiday, String colonist) {
    }

    private CalendarData data;

    public Calendar() {
        ModEvents.BUS.on(GameEvents.CitizenBorn.class, event -> born(event.citizen()));
        ModEvents.BUS.on(GameEvents.CitizenDied.class, event -> died(event.colony(), event.name()));
    }

    public void attach(CalendarData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
    }

    public void tick(MinecraftServer server) {
        if (data == null || server.getTickCount() % CHECK != 0) {
            return;
        }
        for (IColony colony : Colonies.all()) {
            if (colony.getWorld() == null) {
                continue;
            }
            try {
                if (membersInside(server, colony)) {
                    boolean passed = presence(colony).near(gameDay(colony), CHECK, CalendarTable.nearTicks());
                    data.setDirty();
                    if (passed) {
                        newDay(colony);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                ModLog.warn("Could not keep the calendar of colony {}", colony.getName(), e);
            }
        }
    }

    private static boolean membersInside(MinecraftServer server, IColony colony) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Colonies.isInside(player, colony) && Colonies.role(player, colony) != ColonyAccess.Role.OTHER) {
                return true;
            }
        }
        return false;
    }

    private static long gameDay(IColony colony) {
        return colony.getWorld().getDayTime() / GAME_DAY;
    }

    private Presence presence(IColony colony) {
        return data.presence.computeIfAbsent(colony.getID(), id -> new Presence());
    }

    /** A calendar day began: birthdays for newcomers, the year's tally, and the news for the talk code. */
    private void newDay(IColony colony) {
        CalendarDate date = new CalendarDate(presence(colony).day());
        if (date.dayOfYear() == 0) {
            data.thisYear.put(colony.getID(), new int[2]);
        }
        Set<Integer> taken = new HashSet<>();
        String prefix = colony.getID() + ":";
        data.births.forEach((key, day) -> {
            if (key.startsWith(prefix)) {
                taken.add(day);
            }
        });
        List<ICitizenData> birthdays = new ArrayList<>();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            String key = key(citizen);
            Integer day = data.births.get(key);
            if (day == null) {
                day = Birthdays.pick(taken, key, CalendarTable.yearDays());
                data.births.put(key, day);
                taken.add(day);
            } else if (day == date.dayOfYear()) {
                birthdays.add(citizen);
                Moods.birthday(citizen);
            }
        }
        Optional<Holiday> holiday = CalendarTable.holiday(date)
                .filter(h -> !h.special().equals("remembrance") || !departed(colony.getID()).isEmpty());
        ModLog.action("Colony {}: {} {}", colony.getName(), date.english(), holiday.map(Holiday::name).orElse(""));
        ModEvents.BUS.post(new GameEvents.CalendarDay(colony, date, holiday, List.copyOf(birthdays)));
    }

    private void born(ICitizenData citizen) {
        if (data == null) {
            return;
        }
        IColony colony = citizen.getColony();
        Presence presence = presence(colony);
        int today = Math.max(0, presence.day());
        data.births.put(key(citizen), new CalendarDate(today).dayOfYear());
        data.thisYear.computeIfAbsent(colony.getID(), id -> new int[2])[0]++;
        data.setDirty();
    }

    private void died(IColony colony, String name) {
        if (data == null) {
            return;
        }
        presence(colony);
        List<String> list = data.departed.computeIfAbsent(colony.getID(), id -> new ArrayList<>());
        list.remove(name);
        list.add(name);
        while (list.size() > CalendarData.DEPARTED_KEPT) {
            list.remove(0);
        }
        data.thisYear.computeIfAbsent(colony.getID(), id -> new int[2])[1]++;
        data.setDirty();
    }

    /** Today's calendar date in the colony, once its calendar has begun. */
    public Optional<CalendarDate> date(IColony colony) {
        Presence presence = data == null ? null : data.presence.get(colony.getID());
        return presence == null || presence.day() < 0 ? Optional.empty() : Optional.of(new CalendarDate(presence.day()));
    }

    /** Whether today, the current game day, is a calendar day (it passed while members were around). */
    private boolean isToday(IColony colony) {
        Presence presence = data == null ? null : data.presence.get(colony.getID());
        return presence != null && colony.getWorld() != null && presence.isToday(gameDay(colony));
    }

    /** The holiday kept in the colony today, if any. */
    public Optional<Holiday> holiday(IColony colony) {
        if (!isToday(colony)) {
            return Optional.empty();
        }
        return date(colony).flatMap(CalendarTable::holiday)
                .filter(h -> !h.special().equals("remembrance") || !departed(colony.getID()).isEmpty());
    }

    public boolean isBirthday(ICitizenData citizen) {
        return isToday(citizen.getColony()) && birthday(citizen).isPresent()
                && date(citizen.getColony()).map(date -> date.dayOfYear() == birthday(citizen).get()).orElse(false);
    }

    /** The colonist's birthday, as a day of the year. */
    public Optional<Integer> birthday(ICitizenData citizen) {
        return data == null ? Optional.empty() : Optional.ofNullable(data.births.get(key(citizen)));
    }

    /** Whether the item is one of today's holiday's favoured gifts. */
    public boolean favoured(IColony colony, ItemStack stack) {
        return holiday(colony).map(h -> GiftTastes.matches(h.favours(), stack)).orElse(false);
    }

    /** Holidays and birthdays in the colony in the next {@code days} calendar days, soonest first. */
    public List<Upcoming> upcoming(IColony colony, int days) {
        List<Upcoming> out = new ArrayList<>();
        Optional<CalendarDate> today = date(colony);
        if (today.isEmpty()) {
            return out;
        }
        for (int ahead = 1; ahead <= days; ahead++) {
            CalendarDate date = new CalendarDate(today.get().day() + ahead);
            int in = ahead;
            CalendarTable.holiday(date).ifPresent(h -> out.add(new Upcoming(in, h, null)));
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                if (birthday(citizen).map(day -> day == date.dayOfYear()).orElse(false)) {
                    out.add(new Upcoming(in, null, citizen.getName()));
                }
            }
        }
        return out;
    }

    public List<String> departed(int colonyId) {
        return data == null ? List.of() : List.copyOf(data.departed.getOrDefault(colonyId, List.of()));
    }

    /** Born and died in the colony this calendar year. */
    public int[] thisYear(int colonyId) {
        return data == null ? new int[2] : data.thisYear.getOrDefault(colonyId, new int[2]).clone();
    }

    /** For /lcdebug: today becomes calendar day {@code day}, with everything a new day brings. */
    public void setDay(IColony colony, int day) {
        if (data == null || colony.getWorld() == null) {
            return;
        }
        presence(colony).set(day, gameDay(colony));
        data.setDirty();
        newDay(colony);
    }

    /** A colonist brought back from the dead keeps their birthday. */
    public void move(String from, String to) {
        if (data != null && me.bytefox.livingcolonists.Citizens.move(data.births, from, to)) {
            data.setDirty();
        }
    }

    private static String key(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }
}
