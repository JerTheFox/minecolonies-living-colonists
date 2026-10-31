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
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Gifts (#11): what a player puts into a colonist's inventory through MineColonies' inventory window
 * is a gift. The colonist remembers it, warms to the player (at most once a game day) and thanks them
 * in a private line. Server thread only.
 */
public final class GiftWatch {
    private record Opened(ICitizenData citizen, Map<String, Integer> counts) {
    }

    private final TalkService talk;
    private final BondsService bonds;
    private final MemoryService memory;
    private final Map<UUID, Opened> open = new HashMap<>();

    GiftWatch(TalkService talk, BondsService bonds, MemoryService memory) {
        this.talk = talk;
        this.bonds = bonds;
        this.memory = memory;
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
        int worth = 1;
        for (Map.Entry<String, Integer> gift : added.entrySet()) {
            ItemStack stack = sample(citizen.getInventory(), gift.getKey());
            named.add(gift.getValue() + " " + (stack.isEmpty() ? gift.getKey() : ItemNames.of(stack)));
            if (!stack.isEmpty()) {
                worth = Math.max(worth, BondsService.worthOf(stack, gift.getValue()));
            }
        }
        String what = String.join(", ", named);
        String playerName = TalkService.playerName(player);
        Counters.add("gifts");
        ModLog.action("{} gave {} {}", playerName, citizen.getName(), what);
        boolean warmed = bonds.gift(citizen, player.getUUID(), playerName, playerName + " gave me " + what, worth);
        memory.rememberSeen(citizen.getColony(), List.of(citizen.getId()), playerName,
                playerName + " gave " + citizen.getName() + " " + what + ".", Math.min(5, 1 + worth));
        if (warmed) {
            talk.writeFirst(player, citizen, "to thank them, briefly and in your own way, for what they just put in your "
                    + "bag: " + what + ".", false, null, false, Airtime.Kind.NOTE);
        }
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
