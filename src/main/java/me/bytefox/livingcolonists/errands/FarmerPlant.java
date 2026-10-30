/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/FarmerPlantAction.java. Changed: the seed is found by code among the
 * game's names and translations, the player confirms first (so a planted field may be replaced),
 * undo puts the old seed back, and the farmer's AI isn't steered by reflection.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildingextensions.FarmField;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A farmer sets one of their fields to the crop the player wants: an empty field first. */
public final class FarmerPlant implements Errand {
    private final ItemIndex items;

    public FarmerPlant(ItemIndex items) {
        this.items = items;
    }

    @Override
    public String name() {
        return "farmer_plant";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "If you are a farmer: set one of your fields to the crop the player wants "
                + "(\"plant carrots\"); an empty field first, else the nearest one changes crop. The seeds must be "
                + "in the colony for planting.", Map.of("crop", "The crop in plain English words, like wheat, carrot, potato"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        IBuilding hut = context.citizen().getWorkBuilding();
        if (hut == null || !hut.getBuildingType().getRegistryName().getPath().equals("farmer")) {
            return new Result.Answer(Errand.error("You aren't a farmer: only farmers tend fields. Suggest asking one."));
        }
        String crop = Errand.string(arguments, "crop");
        String locale = context.player().clientInformation().language();
        Item seed = seed(crop, locale);
        if (seed == null) {
            return new Result.Answer(Errand.notFound("crop", crop));
        }
        IColony colony = context.colony();
        List<FarmField> fields = fields(colony, hut.getPosition());
        if (fields.isEmpty()) {
            return new Result.Answer(Errand.error("You have no fields yet: a field has to be placed and given to your farm."));
        }
        if (fields.stream().anyMatch(field -> field.getSeed().is(seed))) {
            return new Result.Answer(Errand.error("One of your fields already grows that. Nothing to change."));
        }
        BlockPos from = hut.getPosition();
        FarmField field = fields.stream().filter(f -> f.getSeed().isEmpty()).min(Comparator.comparingDouble(f -> from.distSqr(f.getPosition())))
                .orElseGet(() -> fields.stream().min(Comparator.comparingDouble(f -> from.distSqr(f.getPosition()))).orElseThrow());
        ItemStack before = field.getSeed().copy();
        Component seedName = new ItemStack(seed).getHoverName();
        Component question = before.isEmpty() ? Component.translatable("livingcolonists.order.plant.ask", seedName)
                : Component.translatable("livingcolonists.order.plant.replace", before.getHoverName(), seedName);
        return new Result.Proposal(new Order(name(), "set a field at " + field.getPosition().toShortString() + " to "
                + seedName.getString() + (before.isEmpty() ? "" : " instead of " + before.getHoverName().getString()), question,
                player -> {
            field.setSeed(new ItemStack(seed));
            colony.getServerBuildingManager().markBuildingExtensionsDirty();
            return new Outcome(true, Component.translatable("livingcolonists.order.plant.done", seedName), () -> {
                field.setSeed(before);
                colony.getServerBuildingManager().markBuildingExtensionsDirty();
            });
        }));
    }

    /** The seed for a crop: "wheat" means wheat seeds, a carrot is planted as itself. */
    private Item seed(String crop, String locale) {
        if (crop.isEmpty()) {
            return null;
        }
        for (String words : List.of(crop + " seeds", crop)) {
            if (items.find(words, locale) instanceof ItemMatch.Result.Found<Item> found) {
                return found.value();
            }
        }
        return null;
    }

    /** The farm's own fields, or every field when none belongs to it yet. */
    private static List<FarmField> fields(IColony colony, BlockPos farm) {
        List<FarmField> all = new ArrayList<>();
        colony.getServerBuildingManager().getMatchingBuildingExtension(extension -> {
            if (extension instanceof FarmField field) {
                all.add(field);
            }
            return false;
        });
        List<FarmField> own = all.stream().filter(field -> farm.equals(field.getBuildingId())).toList();
        return own.isEmpty() ? all : own;
    }
}
