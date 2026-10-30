package me.bytefox.livingcolonists.errands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.research.IGlobalResearch;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.ILocalResearchTree;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingUniversity;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.talk.Translations;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * Starts a research at the university, as its window does: MineColonies checks the requirements and
 * takes the cost from the university, then the player's inventory, and tells the player itself.
 * The cost can't be given back, so there is no undo.
 */
public final class StartResearch implements Errand {
    @Override
    public String name() {
        return "start_research";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "Start a research at the university (\"research better swords\"). The cost "
                + "comes from the university or the player's inventory.",
                Map.of("research", "The research's name in plain English words"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        IColony colony = context.colony();
        BuildingUniversity university = null;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (building instanceof BuildingUniversity candidate
                    && (university == null || candidate.getBuildingLevel() > university.getBuildingLevel())) {
                university = candidate;
            }
        }
        if (university == null || university.getBuildingLevel() == 0) {
            return new Result.Answer(Errand.error("The colony has no university yet, so no research can start."));
        }
        String words = Errand.string(arguments, "research");
        String locale = context.player().clientInformation().language();
        ILocalResearchTree local = colony.getResearchManager().getResearchTree();
        List<ItemMatch.Candidate<IGlobalResearch>> open = open(university, local, locale);
        BuildingUniversity at = university;
        return switch (ItemMatch.find(words, open)) {
            case ItemMatch.Result.Found<IGlobalResearch> found -> propose(colony, at, found.value());
            case ItemMatch.Result.Ambiguous<IGlobalResearch> ambiguous -> {
                JsonObject reply = Errand.error("Several researches fit \"" + words + "\": ask which one is meant.");
                JsonArray options = new JsonArray();
                ambiguous.options().stream().limit(ItemMatch.MAX_OPTIONS).forEach(option -> options.add(name(option.value())));
                reply.add("options", options);
                yield new Result.Answer(reply);
            }
            case ItemMatch.Result.None<IGlobalResearch> none -> new Result.Answer(Errand.error("No research that can "
                    + "start now is called \"" + words + "\": it may be done, running, locked behind another one, or "
                    + "need a higher university. Names are looked up in English."));
        };
    }

    private static Result propose(IColony colony, BuildingUniversity university, IGlobalResearch research) {
        Component name = MutableComponent.create(research.getName());
        List<String> cost = new ArrayList<>();
        for (SizedIngredient ingredient : research.getCostList()) {
            var items = ingredient.getItems();
            if (items.length > 0) {
                cost.add(ingredient.count() + " " + ItemNames.of(items[0]));
            }
        }
        String costText = cost.isEmpty() ? "-" : String.join(", ", cost);
        return new Result.Proposal(new Order("start_research", "start the research " + name.getString() + " (costs "
                + costText + ")", Component.translatable("livingcolonists.order.research.ask", name, costText), player -> {
            ILocalResearchTree tree = colony.getResearchManager().getResearchTree();
            tree.attemptBeginResearch(player, colony, university, research);
            boolean started = tree.getResearch(research.getBranch(), research.getId()) != null;
            return new Outcome(started, Component.translatable(started ? "livingcolonists.order.research.done"
                    : "livingcolonists.order.research.refused", name), null);
        }));
    }

    /** Researches that can start now: not begun, not hidden, their parent done and the university high enough. */
    private static List<ItemMatch.Candidate<IGlobalResearch>> open(BuildingUniversity university, ILocalResearchTree local,
                                                                   String locale) {
        IGlobalResearchTree global = IGlobalResearchTree.getInstance();
        List<ItemMatch.Candidate<IGlobalResearch>> open = new ArrayList<>();
        for (ResourceLocation branch : global.getBranches()) {
            Deque<ResourceLocation> todo = new ArrayDeque<>(global.getPrimaryResearch(branch));
            Set<ResourceLocation> seen = new LinkedHashSet<>();
            while (!todo.isEmpty()) {
                ResourceLocation id = todo.poll();
                IGlobalResearch research = id == null || !seen.add(id) ? null : global.getResearch(branch, id);
                if (research == null) {
                    continue;
                }
                todo.addAll(research.getChildren());
                if (research.isHidden() || local.getResearch(branch, id) != null || !research.canResearch(university, local)) {
                    continue;
                }
                Set<String> names = new LinkedHashSet<>();
                names.add(name(research));
                String key = research.getName().getKey();
                for (String language : new LinkedHashSet<>(List.of("ru_ru", locale))) {
                    String translated = Translations.of("minecolonies", language).get(key);
                    if (translated != null && !translated.isBlank()) {
                        names.add(translated);
                    }
                }
                open.add(new ItemMatch.Candidate<>(research, id.toString(), names));
            }
        }
        return open;
    }

    private static String name(IGlobalResearch research) {
        return MutableComponent.create(research.getName()).getString();
    }
}
