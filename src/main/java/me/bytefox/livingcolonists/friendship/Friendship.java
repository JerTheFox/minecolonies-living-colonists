package me.bytefox.livingcolonists.friendship;

import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.events.ModEvents;

/**
 * Friendship levels with players (#67): the first time a colonist reaches a warmer level with a player it is a milestone
 * ({@link GameEvents.FriendshipMilestone}, which the talk code turns into a private line), and a friendship cools by the
 * colony calendar when the player stays away from the colonist. No model here. Server thread only.
 */
public final class Friendship {
    private final BondsService bonds;
    private final Calendar calendar;

    public Friendship(BondsService bonds, Calendar calendar) {
        this.bonds = bonds;
        this.calendar = calendar;
        ModEvents.BUS.on(GameEvents.RapportChanged.class, this::changed);
        ModEvents.BUS.on(GameEvents.CalendarDay.class, day -> bonds.cool(day.colony().getID(), day.date().day()));
    }

    private void changed(GameEvents.RapportChanged change) {
        calendar.date(change.citizen().getColony()).ifPresent(date -> bonds.contact(change.citizen(), change.player(), date.day()));
        FriendshipLevels.Level level = FriendshipLevels.level(change.after());
        if (level.rank() <= bonds.reached(change.citizen(), change.player())) {
            return;
        }
        bonds.reach(change.citizen(), change.player(), level.rank());
        // A level first reached by a step down (an old friendship from before levels were kept) is no occasion.
        if (change.after() > change.before() && FriendshipLevels.milestone(level.id()).isPresent()) {
            ModEvents.BUS.post(new GameEvents.FriendshipMilestone(change.citizen(), change.player(), level.id()));
        }
    }
}
