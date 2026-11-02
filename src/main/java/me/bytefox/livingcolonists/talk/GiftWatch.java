package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.inventory.container.ContainerCitizenInventory;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Gifts;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.friendship.GiftTastes;
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.personality.Personality;
import me.bytefox.livingcolonists.personality.PersonalityService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Gifts (#11): what a player puts into a colonist's inventory through MineColonies' inventory window
 * is a gift. The colonist remembers it, takes it by their tastes (#67: two gifts a week count) and
 * reacts in a private line. Server thread only.
 */
public final class GiftWatch {
    private record Opened(ICitizenData citizen, Map<String, Integer> counts) {
    }

    private final TalkService talk;
    private final BondsService bonds;
    private final MemoryService memory;
    private final PersonalityService personalities;
    private final Map<UUID, Opened> open = new HashMap<>();

    GiftWatch(TalkService talk, BondsService bonds, MemoryService memory, PersonalityService personalities) {
        this.talk = talk;
        this.bonds = bonds;
        this.memory = memory;
        this.personalities = personalities;
    }

    public void opened(ServerPlayer player, AbstractContainerMenu menu) {
        if (menu instanceof ContainerCitizenInventory inventory && inventory.getCitizenData() instanceof ICitizenData citizen) {
            open.put(player.getUUID(), new Opened(citizen, counts(citizen.getInventory())));
        }
    }

    public void closed(ServerPlayer player, AbstractContainerMenu menu) {
        Opened before = open.remove(player.getUUID());
        if (before == null || !(menu instanceof ContainerCitizenInventory)) {
            return;
        }
        ICitizenData citizen = before.citizen();
        Map<String, Integer> added = Gifts.added(before.counts(), counts(citizen.getInventory()));
        if (added.isEmpty()) {
            return;
        }
        List<String> named = new ArrayList<>();
        List<String> english = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        Map<String, Taste> tastes = new HashMap<>();
        String job = citizen.getJob() == null ? "" : citizen.getJob().getJobRegistryEntry().getKey().getPath();
        Personality personality = personalities.get(citizen);
        String archetype = personality == null ? "" : personality.archetype();
        String citizenKey = citizen.getColony().getID() + ":" + citizen.getId();
        int worth = 1;
        for (Map.Entry<String, Integer> gift : added.entrySet()) {
            ItemStack stack = sample(citizen.getInventory(), gift.getKey());
            named.add(gift.getValue() + " " + (stack.isEmpty() ? gift.getKey() : ItemNames.of(stack)));
            if (!stack.isEmpty()) {
                worth = Math.max(worth, BondsService.worthOf(stack, gift.getValue()));
                english.add(stack.getItem().getDescription().getString());
                stacks.add(stack);
                Taste taste = GiftTastes.taste(job, archetype, citizenKey, stack);
                // On a holiday its gifts are loved by everyone who doesn't mind them (#67): flowers in spring.
                if ((taste == Taste.LIKE || taste == Taste.NEUTRAL) && talk.calendar().favoured(citizen.getColony(), stack)) {
                    taste = Taste.LOVE;
                }
                tastes.put(gift.getKey(), taste);
            }
        }
        Taste taste = GiftTastes.gift(job, archetype, citizenKey, stacks);
        if (tastes.containsValue(Taste.LOVE)) {
            taste = Taste.LOVE;
        }
        boolean birthday = talk.calendar().isBirthday(citizen);
        String what = String.join(", ", named);
        String playerName = Audience.playerName(player);
        Counters.add("gifts");
        ModLog.action("{} gave {} {}", playerName, citizen.getName(), what);
        boolean counted = bonds.gift(citizen, player.getUUID(), playerName, playerName + " gave me " + what, worth, english,
                taste, tastes, birthday ? me.bytefox.livingcolonists.friendship.CalendarTable.birthdayGift() : 1);
        memory.rememberSeen(citizen.getColony(), List.of(citizen.getId()), playerName,
                playerName + " gave " + citizen.getName() + " " + what + ".", Math.min(5, 1 + worth));
        if (counted) {
            talk.writeFirst(player, citizen, reaction(taste) + " what they just put in your bag: " + what + "."
                    + (birthday ? " It is your birthday today, and they remembered." : ""),
                    false, null, false, Airtime.Kind.NOTE);
        }
    }

    /** How the colonist takes the gift (#67), decided by code from their tastes. */
    private static String reaction(Taste taste) {
        return switch (taste) {
            case LOVE -> "to thank them warmly, in your own way: it is one of your favourite things, and you are delighted with";
            case LIKE -> "to thank them, briefly and in your own way, glad of";
            case NEUTRAL -> "to thank them, briefly and politely, for";
            case DISLIKE -> "to thank them politely but plainly without enthusiasm, as you don't care for";
            case HATE -> "to tell them, in your own way and without cruelty, that you can't stand";
        };
    }

    void clear() {
        open.clear();
    }

    private static Map<String, Integer> counts(InventoryCitizen inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static ItemStack sample(InventoryCitizen inventory, String item) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
