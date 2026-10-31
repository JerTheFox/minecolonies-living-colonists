package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.talk.Translations;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Orders a building's next level (or its first, for a placed hut), as the building's own window
 * does: the builders take the work order, MineColonies checks research, the town hall and the
 * parent building and tells the player itself. Undo cancels the work order.
 */
public final class UpgradeBuilding implements Errand {
    @Override
    public String name() {
        return "upgrade_building";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "Order the builders to build a colony building's next level. A building placed but not built yet "
                + "(level 0) gets built for the first time this way: \"build the hut\", \"start building it\", \"take the order for "
                + "the hut\" mean this too: the order is placed by calling this, it doesn't exist before.",
                Map.of("building", "The building in plain words, in English, like bakery, warehouse or town hall"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String words = Errand.string(arguments, "building");
        String locale = context.player().clientInformation().language();
        IColony colony = context.colony();
        return switch (ItemMatch.find(words, candidates(colony, locale))) {
            case ItemMatch.Result.Found<IBuilding> found -> order(colony, found.value());
            case ItemMatch.Result.Ambiguous<IBuilding> ambiguous -> {
                JsonObject reply = Errand.error("Several buildings fit \"" + words + "\": ask which one is meant.");
                JsonArray options = new JsonArray();
                ambiguous.options().stream().limit(ItemMatch.MAX_OPTIONS).forEach(option -> options.add(describe(option.value())));
                reply.add("options", options);
                yield new Result.Answer(reply);
            }
            case ItemMatch.Result.None<IBuilding> none -> new Result.Answer(Errand.notFound("building in the colony", words));
        };
    }

    private static Result order(IColony colony, IBuilding building) {
        int level = building.getBuildingLevel();
        if (level >= building.getMaxBuildingLevel()) {
            return new Result.Answer(Errand.error(describe(building) + " is already at its highest level."));
        }
        if (hasWorkOrder(colony, building.getPosition())) {
            return new Result.Answer(Errand.error(describe(building) + " already has a work order waiting."));
        }
        Component name = Component.translatable(building.getBuildingType().getTranslationKey());
        Component question = Component.translatable("livingcolonists.order.upgrade.ask", name, level + 1);
        return new Result.Proposal(new Order("upgrade_building", "build " + describe(building) + " up to level "
                + (level + 1), question, player -> {
            building.requestUpgrade(player, BlockPos.ZERO);
            if (!hasWorkOrder(colony, building.getPosition())) {
                return new Outcome(false, Component.translatable("livingcolonists.order.upgrade.refused", name), null);
            }
            return new Outcome(true, Component.translatable("livingcolonists.order.upgrade.done", name, level + 1),
                    building::removeWorkOrder);
        }));
    }

    private static boolean hasWorkOrder(IColony colony, BlockPos position) {
        return colony.getWorkManager().getWorkOrders().values().stream().anyMatch(order -> position.equals(order.getLocation()));
    }

    static List<ItemMatch.Candidate<IBuilding>> candidates(IColony colony, String locale) {
        List<ItemMatch.Candidate<IBuilding>> candidates = new ArrayList<>();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            var type = building.getBuildingType();
            Set<String> names = new LinkedHashSet<>();
            names.add(displayName(building));
            for (String language : new LinkedHashSet<>(List.of("en_us", "ru_ru", locale))) {
                String name = Translations.of(type.getRegistryName().getNamespace(), language).get(type.getTranslationKey());
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            if (building.getCustomName() != null && !building.getCustomName().isBlank()) {
                names.add(building.getCustomName());
            }
            candidates.add(new ItemMatch.Candidate<>(building, type.getRegistryName().toString(), names));
        }
        return candidates;
    }

    private static String displayName(IBuilding building) {
        return Component.translatable(building.getBuildingType().getTranslationKey()).getString();
    }

    private static String describe(IBuilding building) {
        BlockPos pos = building.getPosition();
        return "the " + ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey())) + " (level "
                + building.getBuildingLevel() + ", at " + pos.getX() + " "
                + pos.getY() + " " + pos.getZ() + ")";
    }
}
