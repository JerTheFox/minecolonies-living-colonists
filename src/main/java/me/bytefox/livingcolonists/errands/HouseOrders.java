package me.bytefox.livingcolonists.errands;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.inventory.api.CombinedItemHandler;
import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.talk.Addressing;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Orders about the colony's buildings that their own windows give: a dish on the restaurant's
 * menu, sorting the warehouse, and moving a colonist into another home.
 */
public final class HouseOrders {
    private HouseOrders() {
    }

    public static List<Errand> all(ItemIndex items) {
        return List.of(new MenuDish(items), new SortWarehouse(), new MoveHome());
    }

    private static Component nameOf(IBuilding building) {
        return Component.translatable(building.getBuildingType().getTranslationKey());
    }

    private static IBuilding first(IColony colony, String path) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(b -> b.getBuildingType().getRegistryName().getPath().equals(path) && b.getBuildingLevel() > 0)
                .findFirst().orElse(null);
    }

    /** Puts a dish on the restaurant's menu, so the cooks keep it in stock; undo takes it off. */
    private static final class MenuDish implements Errand {
        private final ItemIndex items;

        MenuDish(ItemIndex items) {
            this.items = items;
        }

        @Override
        public String name() {
            return "add_to_menu";
        }

        @Override
        public ToolSpec spec() {
            return Errands.orderSpec(name(), "Put a dish on the restaurant's menu, so the cooks keep it in stock "
                    + "(\"put baked potatoes on the menu\").", Map.of("dish", "The food in plain English words"));
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            IBuilding restaurant = first(context.colony(), "cook");
            RestaurantMenuModule menu = restaurant == null ? null : restaurant.getFirstModuleOccurance(RestaurantMenuModule.class);
            if (menu == null) {
                return new Result.Answer(Errand.error("The colony has no restaurant with a menu yet."));
            }
            String words = Errand.string(arguments, "dish");
            if (!(items.find(words, context.player().clientInformation().language()) instanceof ItemMatch.Result.Found<Item> found)) {
                return new Result.Answer(Errand.notFound("single food", words));
            }
            ItemStack dish = new ItemStack(found.value());
            if (dish.get(DataComponents.FOOD) == null) {
                return new Result.Answer(Errand.error(ItemNames.of(dish) + " isn't food."));
            }
            if (menu.getMenu().stream().anyMatch(item -> item.getItem() == found.value())) {
                return new Result.Answer(Errand.error(ItemNames.of(dish) + " is already on the menu."));
            }
            Component name = dish.getHoverName();
            return new Result.Proposal(new Order(name(), "put " + name.getString() + " on the restaurant's menu",
                    Component.translatable("livingcolonists.order.menu.ask", name), player -> {
                menu.addMenuItem(dish);
                restaurant.markDirty();
                return new Outcome(true, Component.translatable("livingcolonists.order.menu.done", name), () -> {
                    menu.removeMenuItem(dish);
                    restaurant.markDirty();
                });
            }));
        }
    }

    /** Sorts the warehouse's racks, as its window's button does. Sorting needs no undo. */
    private static final class SortWarehouse implements Errand {
        @Override
        public String name() {
            return "sort_warehouse";
        }

        @Override
        public ToolSpec spec() {
            return Errands.orderSpec(name(), "Sort the warehouse's racks (\"tidy up the warehouse\").", Map.of());
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            IBuilding warehouse = first(context.colony(), "warehouse");
            if (warehouse == null || !warehouse.canSort()) {
                return new Result.Answer(Errand.error("The warehouse can't be sorted: it has to reach a higher level first."));
            }
            return new Result.Proposal(new Order(name(), "sort the warehouse",
                    Component.translatable("livingcolonists.order.sort.ask"), player -> {
                if (!(warehouse.getItemHandlerCap() instanceof CombinedItemHandler racks)) {
                    return new Outcome(false, Component.translatable("livingcolonists.order.failed"), null);
                }
                warehouse.sort(player.level().registryAccess(), racks);
                return new Outcome(true, Component.translatable("livingcolonists.order.sort.done"), null);
            }));
        }
    }

    /** Moves a colonist (by default the one asked) into a home with a free bed; undo moves them back. */
    private static final class MoveHome implements Errand {
        @Override
        public String name() {
            return "move_home";
        }

        @Override
        public ToolSpec spec() {
            return Errands.orderSpec(name(), "Move a colonist into another home with a free bed (\"move into the house "
                    + "by the river\", \"let Noe live in the town hall\").", Map.of(
                    "home", "The home in plain English words, like residence or tavern, or its custom name",
                    "colonist", "Who moves, as the colony spells the name; empty for yourself"));
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            IColony colony = context.colony();
            ICitizenData citizen = context.citizen();
            String who = Errand.string(arguments, "colonist");
            if (!who.isEmpty()) {
                List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
                List<Integer> named = Addressing.allMentioned(who, citizens.stream().map(ICitizenData::getName).toList());
                if (named.isEmpty()) {
                    return new Result.Answer(Errand.error("Nobody in the colony is called \"" + who + "\"."));
                }
                citizen = citizens.get(named.get(0));
            }
            String words = Errand.string(arguments, "home");
            List<ItemMatch.Candidate<IBuilding>> homes = UpgradeBuilding.candidates(colony, context.player().clientInformation().language())
                    .stream().filter(c -> c.value().getFirstModuleOccurance(LivingBuildingModule.class) != null).toList();
            IBuilding home;
            switch (ItemMatch.find(words, homes)) {
                case ItemMatch.Result.Found<IBuilding> found -> home = found.value();
                case ItemMatch.Result.Ambiguous<IBuilding> ambiguous -> {
                    home = ambiguous.options().stream().map(ItemMatch.Candidate::value)
                            .filter(b -> !b.getFirstModuleOccurance(LivingBuildingModule.class).isFull()).findFirst().orElse(null);
                    if (home == null) {
                        return new Result.Answer(Errand.error("Every home that fits \"" + words + "\" is full."));
                    }
                }
                case ItemMatch.Result.None<IBuilding> none -> {
                    return new Result.Answer(Errand.notFound("home in the colony", words));
                }
            }
            LivingBuildingModule beds = home.getFirstModuleOccurance(LivingBuildingModule.class);
            if (home.equals(citizen.getHomeBuilding())) {
                return new Result.Answer(Errand.error(citizen.getName() + " already lives there."));
            }
            if (beds.isFull()) {
                return new Result.Answer(Errand.error("That home has no free bed."));
            }
            ICitizenData mover = citizen;
            IBuilding target = home;
            IBuilding old = mover.getHomeBuilding();
            Component name = nameOf(target);
            return new Result.Proposal(new Order(name(), "move " + mover.getName() + " into the " + name.getString(),
                    Component.translatable("livingcolonists.order.home.ask", mover.getName(), name), player -> {
                LivingBuildingModule oldBeds = old == null ? null : old.getFirstModuleOccurance(LivingBuildingModule.class);
                if (oldBeds != null) {
                    oldBeds.removeCitizen(mover);
                }
                if (!beds.assignCitizen(mover)) {
                    if (oldBeds != null) {
                        oldBeds.assignCitizen(mover);
                    }
                    return new Outcome(false, Component.translatable("livingcolonists.order.failed"), null);
                }
                return new Outcome(true, Component.translatable("livingcolonists.order.home.done", mover.getName(), name), () -> {
                    beds.removeCitizen(mover);
                    if (oldBeds != null) {
                        oldBeds.assignCitizen(mover);
                    }
                });
            }));
        }
    }
}
