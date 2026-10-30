/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/PrioritizeAction.java. Changed: only "to the front of the queue",
 * the building is found by code, the player confirms first, and undo restores the old priority.
 */
package me.bytefox.livingcolonists.errands;

import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.network.chat.Component;

/**
 * Moves a building's work order to the front of the builders' queue: MineColonies gives builders
 * their work orders sorted by priority. A builder busy with another job finishes it first.
 */
public final class Prioritize implements Errand {
    /** The lowest priority that puts an order first. */
    private static final int TOP = 10;

    @Override
    public String name() {
        return "prioritize";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "Move a building's work order to the front of the builders' queue (\"build the "
                + "hospital first\"). The building must already have a work order.",
                Map.of("building", "The building in plain English words, like hospital or guard tower"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String words = Errand.string(arguments, "building");
        IColony colony = context.colony();
        var found = ItemMatch.find(words, UpgradeBuilding.candidates(colony, context.player().clientInformation().language()));
        if (!(found instanceof ItemMatch.Result.Found<IBuilding> building)) {
            return new Result.Answer(Errand.error(found instanceof ItemMatch.Result.None<IBuilding>
                    ? "The colony has no building called \"" + words + "\"." : "Several buildings fit \"" + words
                    + "\": ask which one is meant, or look at build_status."));
        }
        Optional<IServerWorkOrder> order = colony.getWorkManager().getWorkOrders().values().stream()
                .filter(o -> building.value().getPosition().equals(o.getLocation())).findFirst();
        if (order.isEmpty()) {
            return new Result.Answer(Errand.error("That building has no work order to move: it would have to be ordered first."));
        }
        int top = colony.getWorkManager().getWorkOrders().values().stream().mapToInt(IServerWorkOrder::getPriority).max().orElse(0);
        if (order.get().getPriority() >= top && top > 0) {
            return new Result.Answer(Errand.error("It is already first in the builders' queue."));
        }
        int before = order.get().getPriority();
        Component name = Component.translatable(building.value().getBuildingType().getTranslationKey());
        return new Result.Proposal(new Order("prioritize", "move " + name.getString() + " to the front of the builders' queue",
                Component.translatable("livingcolonists.order.priority.ask", name), player -> {
            order.get().setPriority(Math.max(TOP, top + 1));
            colony.getWorkManager().setDirty(true);
            return new Outcome(true, Component.translatable("livingcolonists.order.priority.done", name), () -> {
                order.get().setPriority(before);
                colony.getWorkManager().setDirty(true);
            });
        }));
    }
}
