/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/CheckStockAction.java. Changed: the item is found by code among the
 * game's names and translations, every fitting kind is counted when several fit, network storage is
 * left out, what other buildings keep is told apart.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

/** How much of an item the colony's warehouses hold, and what other buildings keep of it. */
public final class CheckStock implements Errand {
    private final ItemIndex items;

    public CheckStock(ItemIndex items) {
        this.items = items;
    }

    @Override
    public String name() {
        return "check_stock";
    }

    @Override
    public ToolSpec spec() {
        return Errands.spec(name(), "How much of an item the colony's warehouses hold right now, and which buildings keep some for their work. Use it when someone "
                + "asks how much of something is in stock.", "item",
                "The item in plain words, in English, like iron ingot, oak planks or bread");
    }

    @Override
    public boolean isOrder() {
        return false;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String words = Errand.string(arguments, "item");
        if (words.isEmpty()) {
            return new Result.Answer(Errand.error("Say which item."));
        }
        String locale = context.player() == null ? "en_us" : context.player().clientInformation().language();
        return switch (items.find(words, locale)) {
            case ItemMatch.Result.Found<Item> found -> new Result.Answer(count(context.colony(), found.value()));
            case ItemMatch.Result.Ambiguous<Item> ambiguous -> new Result.Answer(several(context.colony(), words,
                    ambiguous.options().stream().map(ItemMatch.Candidate::value).toList()));
            case ItemMatch.Result.None<Item> none -> new Result.Answer(Errand.notFound("item", words));
        };
    }

    /**
     * Several items fit the words ("wool"): code counts each, so the colonist says what is really
     * there instead of reading the list of possible items as stock.
     */
    private static JsonObject several(IColony colony, String words, List<Item> options) {
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("asked_for", words);
        JsonArray inStock = new JsonArray();
        for (Item item : options) {
            int[] counted = counted(colony, item);
            if (counted[1] == 0) {
                reply.addProperty("info", "The colony has no warehouse yet.");
                held(reply, colony, options);
                return reply;
            }
            if (counted[0] > 0) {
                JsonObject entry = new JsonObject();
                entry.addProperty("item", item.getDescription().getString());
                entry.addProperty("count", counted[0]);
                inStock.add(entry);
            }
        }
        reply.add("in_stock", inStock);
        held(reply, colony, options);
        reply.addProperty("info", inStock.isEmpty() ? "None of the kinds of " + words + " checked is in stock."
                : "Only these kinds are in stock; the other " + (options.size() - inStock.size()) + " kinds are not.");
        return reply;
    }

    private static JsonObject count(IColony colony, Item item) {
        int[] counted = counted(colony, item);
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("item", item.getDescription().getString());
        if (counted[1] == 0) {
            reply.addProperty("info", "The colony has no warehouse yet.");
        } else {
            reply.addProperty("count", counted[0]);
        }
        // Also without a warehouse: a young colony's stock is all in its huts.
        held(reply, colony, List.of(item));
        return reply;
    }

    /** How many buildings with the item to name at most. */
    private static final int MAX_HOLDERS = 3;

    /**
     * What other buildings keep of the items, most first: a smeltery's ingots, a builder's materials. Not stock:
     * it is there for their work, so it is told apart and never added to the warehouse count.
     */
    private static void held(JsonObject reply, IColony colony, List<Item> items) {
        List<Map.Entry<String, Integer>> holders = new ArrayList<>();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            IItemHandler inventory = building.getItemHandlerCap();
            if (inventory == null || building.getBuildingType().getRegistryName().getPath().equals("warehouse")) {
                continue;
            }
            int count = 0;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (items.contains(stack.getItem())) {
                    count += stack.getCount();
                }
            }
            if (count > 0) {
                holders.add(Map.entry(ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey())), count));
            }
        }
        if (holders.isEmpty()) {
            return;
        }
        holders.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        JsonArray kept = new JsonArray();
        for (Map.Entry<String, Integer> holder : holders.subList(0, Math.min(MAX_HOLDERS, holders.size()))) {
            JsonObject entry = new JsonObject();
            entry.addProperty("building", holder.getKey());
            entry.addProperty("count", holder.getValue());
            kept.add(entry);
        }
        reply.add("kept_in_buildings_for_their_work", kept);
        reply.addProperty("note", "Not in the warehouse: these buildings keep it for their own work, so a courier can't "
                + "just take it.");
    }

    /** {total, warehouses}. */
    private static int[] counted(IColony colony, Item item) {
        int total = 0;
        int warehouses = 0;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (!building.getBuildingType().getRegistryName().getPath().equals("warehouse")) {
                continue;
            }
            warehouses++;
            for (BlockPos pos : building.getContainers()) {
                BlockEntity entity = colony.getWorld().getBlockEntity(pos);
                if (entity instanceof AbstractTileEntityRack rack) {
                    total += rack.getItemCount(stack -> stack.is(item));
                }
            }
        }
        return new int[]{total, warehouses};
    }
}
