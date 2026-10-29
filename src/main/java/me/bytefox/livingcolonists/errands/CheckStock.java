/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/CheckStockAction.java. Changed: the item is found by code among the
 * game's names and translations, several fitting items are named back, network storage is left out.
 */
package me.bytefox.livingcolonists.errands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;

/** How much of an item the colony's warehouses hold. */
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
        return Errands.spec(name(), "How much of an item the colony's warehouses hold right now. Use it when someone "
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
            case ItemMatch.Result.Ambiguous<Item> ambiguous -> {
                JsonObject reply = Errand.error("Several items fit \"" + words + "\": ask which one is meant.");
                JsonArray options = new JsonArray();
                ambiguous.options().forEach(option -> options.add(option.value().getDescription().getString()));
                reply.add("options", options);
                yield new Result.Answer(reply);
            }
            case ItemMatch.Result.None<Item> none -> new Result.Answer(Errand.error("There is no item called \""
                    + words + "\"."));
        };
    }

    private static JsonObject count(IColony colony, Item item) {
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
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("item", item.getDescription().getString());
        if (warehouses == 0) {
            reply.addProperty("info", "The colony has no warehouse yet.");
        } else {
            reply.addProperty("count", total);
        }
        return reply;
    }
}
