package me.bytefox.livingcolonists.talk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Dice;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.friendship.Calendar;
import me.bytefox.livingcolonists.friendship.CalendarDate;
import me.bytefox.livingcolonists.friendship.GiftTastes;
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.mood.Conditions;
import me.bytefox.livingcolonists.network.TalkMenuState;
import me.bytefox.livingcolonists.perception.ColonistFacts;
import me.bytefox.livingcolonists.perception.PerceptionService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Talking to a colonist without a language model (#68), as in Stardew Valley: a menu of things to say, and answers
 * made by code from what is really so (their happiness, hunger, illness, work, tastes, birthday), in set lines, warmer
 * or colder by friendship. The first talk of the day, and a compliment once a day, bring a little friendship. No model.
 */
public final class TalkMenu {
    /** What the player can say, in the menu's order. */
    public static final List<String> OPTIONS = TalkMenuState.OPTIONS;
    /** A little friendship for the first talk of the day, and for a compliment. */
    static final int DAILY_TALK = 2;
    static final int COMPLIMENT = 3;
    private static final long DAY = 24000;

    private final BondsService bonds;
    private final Calendar calendar;
    private final PerceptionService perception;
    private final me.bytefox.livingcolonists.personality.PersonalityService personalities;
    private final Random random = Dice.random();
    /** The game day of each thing done once a day, by {@code what:colony:citizen:player}; not saved. */
    private final Map<String, Long> done = new HashMap<>();

    TalkMenu(BondsService bonds, Calendar calendar, PerceptionService perception,
             me.bytefox.livingcolonists.personality.PersonalityService personalities) {
        this.bonds = bonds;
        this.calendar = calendar;
        this.perception = perception;
        this.personalities = personalities;
    }

    /** The player turns to the colonist: the menu opens with a greeting. */
    public void open(ServerPlayer player, ICitizenData citizen) {
        Component hello = line("menu.hello." + warmth(citizen, player));
        daily(player, citizen);
        send(player, citizen, hello, true);
    }

    /** The player picked something to say. */
    public void choose(ServerPlayer player, ICitizenData citizen, String option) {
        if (!OPTIONS.contains(option)) {
            return;
        }
        daily(player, citizen);
        Component answer = switch (option) {
            case "how" -> how(citizen);
            case "work" -> work(citizen);
            case "likes" -> likes(player, citizen);
            case "birthday" -> birthday(citizen);
            case "compliment" -> compliment(player, citizen);
            default -> line("menu.bye." + warmth(citizen, player));
        };
        Counters.add("menu." + option);
        send(player, citizen, answer, false);
    }

    private void send(ServerPlayer player, ICitizenData citizen, Component line, boolean open) {
        Speaker speaker = Audience.speakerOf(citizen);
        PacketDistributor.sendToPlayer(player, new TalkMenuState(citizen.getColony().getID(), citizen.getId(), speaker.name(),
                speaker.jobTranslationKey() == null ? "" : speaker.jobTranslationKey(), line, open));
        player.sendSystemMessage(Component.literal("<" + speaker.name() + "> → <").append(player.getDisplayName())
                .append("> ").append(line));
    }

    /** The first talk of the game day with this colonist brings a little friendship. */
    private void daily(ServerPlayer player, ICitizenData citizen) {
        if (once("talk", player, citizen)) {
            bonds.friendly(citizen, player.getUUID(), Audience.playerName(player), DAILY_TALK,
                    Audience.playerName(player) + " stopped to talk with me");
        }
    }

    private Component how(ICitizenData citizen) {
        try {
            if (citizen.getCitizenDiseaseHandler() != null && citizen.getCitizenDiseaseHandler().isSick()) {
                return line("menu.how.sick");
            }
            var mourn = citizen.getCitizenMournHandler();
            if (mourn != null && mourn.isMourning() && !mourn.getDeceasedCitizens().isEmpty()) {
                return line("menu.how.mourning", mourn.getDeceasedCitizens().iterator().next());
            }
        } catch (RuntimeException | LinkageError e) {
            // What MineColonies can't tell now is left out.
        }
        if (citizen.getSaturation() < Conditions.HUNGRY) {
            return line("menu.how.hungry");
        }
        if (citizen.getHomeBuilding() == null && !citizen.isChild()) {
            return line("menu.how.homeless");
        }
        double happiness = citizen.getCitizenHappinessHandler().getHappiness(citizen.getColony(), citizen);
        return line(happiness < Conditions.UNHAPPY ? "menu.how.unhappy" : happiness >= 8.5 ? "menu.how.great" : "menu.how.fine");
    }

    private Component work(ICitizenData citizen) {
        if (citizen.isChild()) {
            return line("menu.work.child");
        }
        if (citizen.getJob() == null) {
            return line("menu.work.none");
        }
        ColonistFacts facts = perception.facts(citizen);
        if (facts.construction() != null) {
            return line(facts.missingMaterials().isEmpty() ? "menu.work.building" : "menu.work.building_short");
        }
        if (!facts.playerMustBring().isEmpty() || !facts.stuckRequests().isEmpty()) {
            return line("menu.work.waiting");
        }
        return line("menu.work.fine");
    }

    /** Once a day the colonist tells one thing they like that the player doesn't know yet; it goes into the notebook. */
    private Component likes(ServerPlayer player, ICitizenData citizen) {
        if (!once("likes", player, citizen)) {
            return line("menu.likes.again");
        }
        String key = citizen.getColony().getID() + ":" + citizen.getId();
        String job = citizen.getJob() == null ? "" : citizen.getJob().getJobRegistryEntry().getKey().getPath();
        var known = bonds.knownTastes(citizen, player.getUUID());
        for (String id : candidates(key, job)) {
            boolean told = known.values().stream().anyMatch(list -> list.contains(id));
            if (!told) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)));
                Taste taste = GiftTastes.taste(job, archetype(citizen), key, stack);
                if (taste == Taste.LOVE || taste == Taste.LIKE) {
                    bonds.learnTaste(citizen, player.getUUID(), id, taste);
                    return line(taste == Taste.LOVE ? "menu.likes.love" : "menu.likes.like", stack.getHoverName());
                }
            }
        }
        return line("menu.likes.all");
    }

    /** Things the colonist may love or like, their own odd favourite first. */
    private static List<String> candidates(String citizenKey, String job) {
        List<String> out = new java.util.ArrayList<>();
        ItemStack odd = GiftTastes.oddLove(citizenKey);
        if (!odd.isEmpty()) {
            out.add(BuiltInRegistries.ITEM.getKey(odd.getItem()).toString());
        }
        out.addAll(GiftTastes.namedItems(job));
        return out;
    }

    private String archetype(ICitizenData citizen) {
        var personality = personalities.get(citizen);
        return personality == null ? "" : personality.archetype();
    }

    private Component birthday(ICitizenData citizen) {
        Optional<Integer> day = calendar.birthday(citizen);
        if (day.isEmpty()) {
            return line("menu.birthday.unknown");
        }
        if (calendar.isBirthday(citizen)) {
            return line("menu.birthday.today");
        }
        CalendarDate date = new CalendarDate(day.get());
        return line("menu.birthday.day", Component.translatable("livingcolonists.calendar.season." + CalendarDate.SEASONS[date.season()]),
                date.dayOfSeason());
    }

    private Component compliment(ServerPlayer player, ICitizenData citizen) {
        if (!once("compliment", player, citizen)) {
            return line("menu.compliment.again");
        }
        String warmth = warmth(citizen, player);
        bonds.friendly(citizen, player.getUUID(), Audience.playerName(player), COMPLIMENT,
                Audience.playerName(player) + " said something kind to me");
        ModLog.action("{} complimented {}", Audience.playerName(player), citizen.getName());
        return line("menu.compliment." + warmth);
    }

    private String warmth(ICitizenData citizen, ServerPlayer player) {
        return TalkService.warmth(bonds.playerRapport(citizen, player.getUUID()));
    }

    /** Whether this is the first time today; marks it done. */
    private boolean once(String what, ServerPlayer player, ICitizenData citizen) {
        IColony colony = citizen.getColony();
        long today = colony.getWorld() == null ? 0 : colony.getWorld().getDayTime() / DAY;
        String key = what + ":" + colony.getID() + ":" + citizen.getId() + ":" + player.getUUID();
        Long last = done.put(key, today);
        return last == null || last != today;
    }

    /** A set line of the occasion as a translation, so each player reads it in their own language. */
    private Component line(String occasion, Object... args) {
        return SetLines.key(occasion, random).map(key -> (Component) Component.translatable(key, args))
                .orElse(Component.literal("..."));
    }

    void clear() {
        done.clear();
    }
}
