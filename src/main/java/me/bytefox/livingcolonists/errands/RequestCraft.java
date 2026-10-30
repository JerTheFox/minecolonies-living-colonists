/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/RequestCraftAction.java. Changed: one item per order, found by code
 * among the game's names and translations; the player confirms first; undo cancels the request;
 * carrying the order to the player is left out.
 */
package me.bytefox.livingcolonists.errands;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Orders an item from the colony's crafters through its request system, as a player does by hand:
 * a crafter who knows the recipe makes it and a courier drops it at the postbox (or the town hall),
 * or stocks it into a building the player names.
 */
public final class RequestCraft implements Errand {
    private static final int MAX_COUNT = 512;
    private final ItemIndex items;

    public RequestCraft(ItemIndex items) {
        this.items = items;
    }

    @Override
    public String name() {
        return "request_craft";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "Order something to be made by the colony's crafters (\"craft me a pickaxe\", "
                + "\"order 20 iron ingots\"). Any colonist can pass the order on, it doesn't have to be your craft. "
                + "For something only to be carried from the warehouse this is not the right function.",
                Map.of("item", "The item in plain English words, like iron pickaxe or oak planks",
                        "count", "How many, as a number; 1 if the player didn't say",
                        "to", "A building to stock it into if the player named one, like restaurant; empty otherwise"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String words = Errand.string(arguments, "item");
        int count = Math.max(1, Math.min(MAX_COUNT, number(Errand.string(arguments, "count"))));
        String locale = context.player().clientInformation().language();
        IColony colony = context.colony();
        return switch (items.find(words, locale)) {
            case ItemMatch.Result.Found<Item> found -> order(colony, found.value(), count, Errand.string(arguments, "to"), locale);
            case ItemMatch.Result.Ambiguous<Item> ambiguous -> {
                JsonObject reply = Errand.error("Several items fit \"" + words + "\": ask which one is meant.");
                JsonArray options = new JsonArray();
                ambiguous.options().stream().limit(ItemMatch.MAX_OPTIONS)
                        .forEach(option -> options.add(option.value().getDescription().getString()));
                reply.add("options", options);
                yield new Result.Answer(reply);
            }
            case ItemMatch.Result.None<Item> none -> new Result.Answer(Errand.notFound("item", words));
        };
    }

    private static Result order(IColony colony, Item item, int count, String to, String locale) {
        ItemStack one = new ItemStack(item);
        String crafter = null;
        String idle = null;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (building.getBuildingLevel() <= 0 || building.getModules(ICraftingBuildingModule.class).stream()
                    .noneMatch(module -> module.getFirstRecipe(one) != null)) {
                continue;
            }
            String name = ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey()));
            var workers = building.getAllAssignedCitizen();
            if (workers.isEmpty()) {
                idle = idle == null ? name : idle;
                continue;
            }
            ICitizenData worker = workers.iterator().next();
            crafter = worker.getName() + " at the " + name;
            break;
        }
        String itemName = ItemNames.of(one);
        if (crafter == null) {
            return new Result.Answer(Errand.error(idle != null
                    ? "The " + idle + " has the recipe for " + itemName + " but nobody works there, so it would never be made."
                    : "Nobody in the colony knows how to make " + itemName + ": crafters only make what they were taught."));
        }
        IBuilding target;
        if (to.isEmpty()) {
            target = deliveryTarget(colony);
            if (target == null) {
                return new Result.Answer(Errand.error("The colony has no postbox and no town hall to deliver an order to."));
            }
        } else {
            var found = ItemMatch.find(to, UpgradeBuilding.candidates(colony, locale));
            if (!(found instanceof ItemMatch.Result.Found<IBuilding> building)) {
                return new Result.Answer(Errand.error("Which building to stock it into isn't clear: \"" + to + "\". Ask."));
            }
            target = building.value();
        }
        Component itemText = one.getHoverName();
        Component place = Component.translatable(target.getBuildingType().getTranslationKey());
        String madeBy = crafter;
        return new Result.Proposal(new Order("request_craft", "order " + count + " " + itemName + " (made by " + madeBy
                + ") to the " + place.getString(), Component.translatable("livingcolonists.order.craft.ask", count, itemText, place),
                player -> {
            IToken<?> token = target.createRequest(new Stack(one, count, count), false);
            if (token == null) {
                return new Outcome(false, Component.translatable("livingcolonists.order.craft.refused", itemText), null);
            }
            return new Outcome(true, Component.translatable("livingcolonists.order.craft.done", count, itemText, place),
                    () -> colony.getRequestManager().updateRequestState(token, RequestState.CANCELLED));
        }));
    }

    /** The postbox first, as players expect, else the town hall. */
    private static IBuilding deliveryTarget(IColony colony) {
        IBuilding townHall = null;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            String path = building.getBuildingType().getRegistryName().getPath();
            if (path.equals("postbox")) {
                return building;
            }
            if (path.equals("townhall") && building.getBuildingLevel() > 0) {
                townHall = building;
            }
        }
        return townHall;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
