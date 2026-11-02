package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.Dice;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.events.ModEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Letters and small gifts from colonists in the Colony Notebook (#67): a friend writes now and then on a new calendar
 * day, more often the closer they are; on the Gift Exchange someone who likes the player sends a gift without a name; a
 * new best friend sends a keepsake. Gifts are made from nothing and wait in the notebook until taken. No model here.
 */
public final class Letters {
    /** Letters already taken that stay in the notebook, newest kept. */
    private static final int TAKEN_KEPT = 10;

    private final BondsService bonds;
    private final Calendar calendar;
    private LettersData data;

    public Letters(BondsService bonds, Calendar calendar) {
        this.bonds = bonds;
        this.calendar = calendar;
        ModEvents.BUS.on(GameEvents.CalendarDay.class, this::newDay);
        ModEvents.BUS.on(GameEvents.FriendshipMilestone.class, this::milestone);
    }

    public void attach(LettersData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
    }

    private void newDay(GameEvents.CalendarDay day) {
        if (data == null || !Config.LETTERS.get()) {
            return;
        }
        IColony colony = day.colony();
        Random random = Dice.random();
        Map<UUID, Integer> today = new HashMap<>();
        boolean exchange = day.holiday().map(h -> h.special().equals("gift_exchange")).orElse(false);
        Map<UUID, List<ICitizenData>> liking = new HashMap<>();
        int likes = FriendshipLevels.byId("likes").map(FriendshipLevels.Level::rank).orElse(Integer.MAX_VALUE);
        for (BondsService.Bond bond : bonds.bondsIn(colony)) {
            FriendshipLevels.Level level = FriendshipLevels.level(bond.rapport());
            if (level.rank() >= likes) {
                liking.computeIfAbsent(bond.player(), p -> new ArrayList<>()).add(bond.citizen());
            }
            String kind = LetterTable.kind(level.id());
            String key = colony.getID() + ":" + bond.citizen().getId() + ":" + bond.player();
            Integer last = data.lastLetter.get(key);
            if (kind == null || last != null && day.date().day() - last < LetterTable.weekDays()
                    || today.getOrDefault(bond.player(), 0) >= LetterTable.perPlayerPerDay()
                    || random.nextDouble() >= LetterTable.chance(level.id())) {
                continue;
            }
            send(bond.player(), bond.citizen(), kind, LetterTable.gift(job(bond.citizen()), random), day.date().day(), random);
            data.lastLetter.put(key, day.date().day());
            today.merge(bond.player(), 1, Integer::sum);
        }
        if (exchange) {
            // Someone who likes the player gives a small present without a name.
            liking.forEach((player, citizens) -> {
                ICitizenData giver = citizens.get(random.nextInt(citizens.size()));
                send(player, null, giver, "exchange", LetterTable.gift(job(giver), random), day.date().day(), random);
            });
        }
    }

    /** A new best friend sends a keepsake. */
    private void milestone(GameEvents.FriendshipMilestone event) {
        if (data == null || !Config.LETTERS.get() || !event.level().equals("best")) {
            return;
        }
        int day = calendar.date(event.citizen().getColony()).map(CalendarDate::day).orElse(0);
        send(event.player(), event.citizen(), "keepsake", LetterTable.keepsake(Dice.random()), day, Dice.random());
    }

    private void send(UUID player, ICitizenData citizen, String kind, ItemStack gift, int day, Random random) {
        send(player, citizen, citizen, kind, gift, day, random);
    }

    /** @param signed who signs it; null for a gift without a name */
    private void send(UUID player, ICitizenData signed, ICitizenData from, String kind, ItemStack gift, int day, Random random) {
        Letter letter = new Letter(data.nextId++, signed == null ? "" : signed.getName(), from.getColony().getName(), kind,
                1 + random.nextInt(LetterTable.variants(kind)), gift, day, gift.isEmpty());
        data.letters.computeIfAbsent(player, p -> new ArrayList<>()).add(letter);
        trim(data.letters.get(player));
        data.setDirty();
        Counters.add("letters");
        ModLog.action("{} left a letter ({}) with {} for {}", from.getName(), kind, gift, player);
        var server = from.getColony().getWorld() == null ? null : from.getColony().getWorld().getServer();
        ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.displayClientMessage(signed == null ? Component.translatable("livingcolonists.letter.arrived.secret")
                    : Component.translatable("livingcolonists.letter.arrived", signed.getName()), true);
        }
    }

    private static void trim(List<Letter> list) {
        long taken = list.stream().filter(Letter::taken).count();
        for (int i = 0; i < list.size() && taken > TAKEN_KEPT; ) {
            if (list.get(i).taken()) {
                list.remove(i);
                taken--;
            } else {
                i++;
            }
        }
    }

    private static String job(ICitizenData citizen) {
        return citizen.getJob() == null ? "" : citizen.getJob().getJobRegistryEntry().getKey().getPath();
    }

    /** The player's letters, newest first. */
    public List<Letter> of(UUID player) {
        List<Letter> list = data == null ? List.of() : new ArrayList<>(data.letters.getOrDefault(player, List.of()));
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * The player takes a letter's gift: into their inventory, as much as fits; the rest waits in the notebook. Whether
     * anything was taken.
     */
    public boolean take(ServerPlayer player, long id) {
        List<Letter> list = data == null ? null : data.letters.get(player.getUUID());
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            Letter letter = list.get(i);
            if (letter.id() == id && !letter.taken()) {
                ItemStack gift = letter.gift().copy();
                int before = gift.getCount();
                player.getInventory().add(gift);
                if (gift.getCount() == before) {
                    player.displayClientMessage(Component.translatable("livingcolonists.letter.no_room"), true);
                    return false;
                }
                list.set(i, letter.withGift(gift));
                trim(list);
                data.setDirty();
                return true;
            }
        }
        return false;
    }

    /** For tests and /lcdebug: a letter from this colonist now, whatever the dice say. */
    public void sendNow(UUID player, ICitizenData citizen, String kind) {
        if (data != null) {
            send(player, citizen, kind, LetterTable.gift(job(citizen), Dice.random()),
                    calendar.date(citizen.getColony()).map(CalendarDate::day).orElse(0), Dice.random());
        }
    }
}
