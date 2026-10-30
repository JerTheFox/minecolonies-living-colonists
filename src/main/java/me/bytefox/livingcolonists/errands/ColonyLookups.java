package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.descriptions.ICitizenEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenDiedEvent;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ColonistFacts;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.talk.Addressing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Look-ups over the whole colony, built from what the colonists themselves perceive: what the
 * colony needs from the player, who is unhappy and why, what is being built, how long the food
 * lasts, and where someone or something is.
 */
public final class ColonyLookups {
    private static final int MAX_LINES = 10;
    /** Happiness below this, of 10, counts as unhappy. */
    private static final double UNHAPPY = 6.0;
    /** A rough day's food for one colonist, in hunger points. */
    private static final int NUTRITION_PER_DAY = 10;

    private ColonyLookups() {
    }

    /** A look-up made of a name, a description, an optional parameter and what it reads. */
    private record Lookup(String name, ToolSpec spec, BiFunction<Errand.Context, JsonObject, JsonObject> read)
            implements Errand {
        @Override
        public boolean isOrder() {
            return false;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            return new Result.Answer(read.apply(context, arguments));
        }
    }

    public static List<Errand> all(PerceptionService perception) {
        return List.of(
                lookup("colony_needs", "What the colony is waiting for from players: things nobody in the colony can "
                        + "make, requests nobody has taken on, and materials the builders still lack.", null, null,
                        (context, arguments) -> needs(context.colony(), perception)),
                lookup("unhappy_colonists", "Who in the colony is unhappy, and what drags them down.", null, null,
                        (context, arguments) -> unhappy(context.colony(), perception)),
                lookup("build_status", "What the builders are building or have waiting: each work order, who took it "
                        + "and what material it still lacks.", null, null,
                        (context, arguments) -> builds(context.colony(), perception)),
                lookup("food_status", "How much food the warehouses hold, roughly how many days it lasts the colony, "
                        + "and who is hungry.", null, null,
                        (context, arguments) -> food(context.colony(), perception)),
                lookup("fallen_colonists", "The colonists who died, newest last, and how, from the colony's own records.",
                        null, null, (context, arguments) -> fallen(context.colony())),
                lookup("where_is", "Which way and how far a colonist or a building is from the person asking.",
                        "what", "A colonist's name as the colony spells it, or a building in plain English words",
                        (context, arguments) -> where(context, Errand.string(arguments, "what"))));
    }

    private static Errand lookup(String name, String description, String parameter, String parameterDescription,
                                 BiFunction<Errand.Context, JsonObject, JsonObject> read) {
        return new Lookup(name, Errands.spec(name, description, parameter, parameterDescription), read);
    }

    private static JsonObject needs(IColony colony, PerceptionService perception) {
        JsonArray fromPlayers = new JsonArray();
        JsonArray untaken = new JsonArray();
        JsonArray materials = new JsonArray();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            ColonistFacts facts = perception.facts(citizen);
            facts.playerMustBring().forEach(thing -> add(fromPlayers, citizen.getName() + " needs " + thing));
            facts.stuckRequests().forEach(thing -> add(untaken, citizen.getName() + " waits for " + thing));
            if (facts.construction() != null && !facts.missingMaterials().isEmpty()) {
                add(materials, citizen.getName() + " (building " + facts.construction() + ") lacks "
                        + String.join(", ", facts.missingMaterials()));
            }
        }
        JsonObject reply = ok();
        reply.add("only_players_can_bring", fromPlayers);
        reply.add("nobody_took_on_yet", untaken);
        reply.add("builders_lack", materials);
        return reply;
    }

    private static JsonObject unhappy(IColony colony, PerceptionService perception) {
        record Mood(String name, double happiness, List<String> why) {
        }
        List<Mood> moods = new ArrayList<>();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            ColonistFacts facts = perception.facts(citizen);
            if (facts.happiness() >= 0 && facts.happiness() < UNHAPPY) {
                moods.add(new Mood(citizen.getName(), facts.happiness(), facts.unhappyAbout()));
            }
        }
        moods.sort(Comparator.comparingDouble(Mood::happiness));
        JsonArray list = new JsonArray();
        for (Mood mood : moods.subList(0, Math.min(MAX_LINES, moods.size()))) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", mood.name());
            entry.addProperty("happiness", Math.round(mood.happiness() * 10) / 10.0 + " of 10");
            entry.addProperty("because", mood.why().isEmpty() ? "nothing in particular" : String.join(", ", mood.why()));
            list.add(entry);
        }
        JsonObject reply = ok();
        reply.add("unhappy", list);
        reply.addProperty("colonists", colony.getCitizenManager().getCitizens().size());
        return reply;
    }

    private static JsonObject builds(IColony colony, PerceptionService perception) {
        JsonArray orders = new JsonArray();
        for (IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("what", order.getDisplayName().getString());
            entry.addProperty("kind", order.getWorkOrderType().name().toLowerCase());
            entry.addProperty("to_level", order.getTargetLevel());
            entry.addProperty("builder", builderOf(colony, order));
            orders.add(entry);
            if (orders.size() >= MAX_LINES) {
                break;
            }
        }
        JsonArray lacking = new JsonArray();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            ColonistFacts facts = perception.facts(citizen);
            if (facts.construction() != null) {
                add(lacking, citizen.getName() + " is on " + facts.construction() + (facts.missingMaterials().isEmpty()
                        ? ", with everything at hand" : ", still lacking " + String.join(", ", facts.missingMaterials())));
            }
        }
        JsonObject reply = ok();
        reply.add("work_orders", orders);
        reply.add("builders", lacking);
        return reply;
    }

    private static String builderOf(IColony colony, IServerWorkOrder order) {
        if (!order.isClaimed()) {
            return "nobody yet";
        }
        IBuilding hut = colony.getServerBuildingManager().getBuilding(order.getClaimedBy());
        if (hut == null || hut.getAllAssignedCitizen().isEmpty()) {
            return "a builder's hut without a builder";
        }
        return hut.getAllAssignedCitizen().iterator().next().getName();
    }

    private static JsonObject food(IColony colony, PerceptionService perception) {
        int items = 0;
        int nutrition = 0;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (!building.getBuildingType().getRegistryName().getPath().equals("warehouse")) {
                continue;
            }
            for (BlockPos pos : building.getContainers()) {
                BlockEntity entity = colony.getWorld().getBlockEntity(pos);
                if (!(entity instanceof AbstractTileEntityRack rack)) {
                    continue;
                }
                var inventory = rack.getInventory();
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    var stack = inventory.getStackInSlot(slot);
                    FoodProperties food = stack.get(DataComponents.FOOD);
                    if (food != null) {
                        items += stack.getCount();
                        nutrition += food.nutrition() * stack.getCount();
                    }
                }
            }
        }
        int colonists = colony.getCitizenManager().getCitizens().size();
        JsonArray hungry = new JsonArray();
        boolean restaurant = colony.getServerBuildingManager().getBuildings().values().stream()
                .anyMatch(b -> b.getBuildingType().getRegistryName().getPath().equals("cook"));
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (perception.facts(citizen).saturation() < 8) {
                add(hungry, citizen.getName());
            }
        }
        JsonObject reply = ok();
        reply.addProperty("food_items_in_warehouses", items);
        reply.addProperty("days_it_lasts_roughly", colonists == 0 ? 0 : nutrition / (colonists * NUTRITION_PER_DAY));
        reply.addProperty("restaurant", restaurant);
        reply.add("hungry_now", hungry);
        return reply;
    }

    private static JsonObject where(Errand.Context context, String what) {
        if (what.isEmpty()) {
            return Errand.error("Say who or what.");
        }
        BlockPos from = context.player() != null ? context.player().blockPosition()
                : context.citizen().getEntity().map(entity -> entity.blockPosition()).orElse(null);
        if (from == null) {
            return Errand.error("You can't tell from here.");
        }
        IColony colony = context.colony();
        List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
        List<Integer> named = Addressing.allMentioned(what, citizens.stream().map(ICitizenData::getName).toList());
        if (!named.isEmpty()) {
            ICitizenData citizen = citizens.get(named.get(0));
            var entity = citizen.getEntity();
            if (entity.isEmpty()) {
                return Errand.error(citizen.getName() + " is too far away to see from here.");
            }
            BlockPos at = entity.get().blockPosition();
            JsonObject reply = ok();
            reply.addProperty("who", citizen.getName());
            reply.addProperty("where", Compass.way(at.getX() - from.getX(), at.getZ() - from.getZ()));
            return reply;
        }
        String locale = context.player() == null ? "en_us" : context.player().clientInformation().language();
        return switch (ItemMatch.find(what, UpgradeBuilding.candidates(colony, locale))) {
            case ItemMatch.Result.Found<IBuilding> found -> {
                BlockPos at = found.value().getPosition();
                JsonObject reply = ok();
                reply.addProperty("building", Component.translatable(found.value().getBuildingType().getTranslationKey()).getString());
                reply.addProperty("where", Compass.way(at.getX() - from.getX(), at.getZ() - from.getZ()));
                yield reply;
            }
            case ItemMatch.Result.Ambiguous<IBuilding> ambiguous -> {
                JsonObject reply = ok();
                JsonArray all = new JsonArray();
                for (var option : ambiguous.options().subList(0, Math.min(ItemMatch.MAX_OPTIONS, ambiguous.options().size()))) {
                    BlockPos at = option.value().getPosition();
                    all.add(Component.translatable(option.value().getBuildingType().getTranslationKey()).getString()
                            + " level " + option.value().getBuildingLevel() + ": "
                            + Compass.way(at.getX() - from.getX(), at.getZ() - from.getZ()));
                }
                reply.add("several", all);
                yield reply;
            }
            case ItemMatch.Result.None<IBuilding> none -> Errand.error("There is nobody and no building called \""
                    + what + "\" in the colony.");
        };
    }

    private static JsonObject fallen(IColony colony) {
        List<String> deaths = new ArrayList<>();
        for (IColonyEventDescription entry : colony.getEventDescriptionManager().getEventDescriptions()) {
            if (entry instanceof ICitizenEventDescription citizen && entry.getEventTypeId().getPath().contains("died")) {
                String cause = entry instanceof CitizenDiedEvent died && died.getDeathCause() != null
                        ? " (" + died.getDeathCause() + ")" : "";
                deaths.add(citizen.getCitizenName() + cause);
            }
        }
        JsonArray list = new JsonArray();
        deaths.subList(Math.max(0, deaths.size() - MAX_LINES), deaths.size()).forEach(list::add);
        JsonObject reply = ok();
        reply.add("died", list);
        if (deaths.isEmpty()) {
            reply.addProperty("info", "Nobody in the colony's records has died.");
        }
        return reply;
    }

    private static void add(JsonArray array, String line) {
        if (array.size() < MAX_LINES) {
            array.add(line);
        }
    }

    private static JsonObject ok() {
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        return reply;
    }
}
