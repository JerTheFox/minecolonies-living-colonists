/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/TakeJobAction.java. Changed: the workplace is found by code by its
 * building or profession names in any of the game's languages, the player confirms first, and undo
 * puts the colonist back in their old position; no walking errand.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.talk.JobNames;
import net.minecraft.network.chat.Component;

/** The colonist asked takes a job, or switches to another one, where a position is free. */
public final class TakeJob implements Errand {
    @Override
    public String name() {
        return "take_job";
    }

    @Override
    public ToolSpec spec() {
        return Errands.orderSpec(name(), "You take a job, or switch from your job to another one, when the player tells "
                + "you to (\"become a farmer\", \"go work at the bakery\"). Also when they name the work instead, like "
                + "chopping wood or fishing: taking that job is the only way you would do it.",
                Map.of("workplace", "The profession or the building in plain English words, like farmer, courier, bakery"));
    }

    @Override
    public boolean isOrder() {
        return true;
    }

    private record Position(IBuilding building, WorkerBuildingModule module) {
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        ICitizenData citizen = context.citizen();
        if (citizen.isChild()) {
            return new Result.Answer(Errand.error("You are a child: children go to school, they don't take jobs."));
        }
        String words = Errand.string(arguments, "workplace");
        String locale = context.player().clientInformation().language();
        List<ItemMatch.Candidate<Position>> positions = positions(context.colony(), locale);
        return switch (ItemMatch.find(words, positions)) {
            case ItemMatch.Result.Found<Position> found -> take(citizen, context.colony(), found.value(), positions);
            case ItemMatch.Result.Ambiguous<Position> ambiguous -> {
                // Several fit: the same job in several buildings is fine, the first free one will do.
                Position first = ambiguous.options().get(0).value();
                boolean sameJob = ambiguous.options().stream()
                        .allMatch(option -> option.value().module().getJobEntry().equals(first.module().getJobEntry()));
                if (sameJob) {
                    yield take(citizen, context.colony(), first, positions);
                }
                JsonObject reply = Errand.error("Several workplaces fit \"" + words + "\": ask which one is meant.");
                JsonArray options = new JsonArray();
                ambiguous.options().stream().limit(ItemMatch.MAX_OPTIONS).forEach(option -> options.add(describe(option.value())));
                reply.add("options", options);
                yield new Result.Answer(reply);
            }
            case ItemMatch.Result.None<Position> none -> new Result.Answer(Errand.error("The colony has no workplace "
                    + "for \"" + words + "\": it has to be built first. Say so honestly; nothing changes."));
        };
    }

    private static Result take(ICitizenData citizen, IColony colony, Position wanted,
                               List<ItemMatch.Candidate<Position>> positions) {
        WorkerBuildingModule old = currentModule(citizen);
        if (old != null && old.getJobEntry().equals(wanted.module().getJobEntry())) {
            return new Result.Answer(Errand.error("You already work as " + wanted.module().getJobDisplayName() + "."));
        }
        Position free = wanted.module().isFull() ? positions.stream().map(ItemMatch.Candidate::value)
                .filter(p -> p.module().getJobEntry().equals(wanted.module().getJobEntry()) && !p.module().isFull())
                .findFirst().orElse(null) : wanted;
        if (free == null) {
            return new Result.Answer(Errand.error("Every position as " + wanted.module().getJobDisplayName()
                    + " is taken. Say so honestly; nothing changes" + (old != null ? " and you keep your job" : "")
                    + ". Upgrading the building may add positions."));
        }
        Component job = Component.translatable(free.module().getJobEntry().getTranslationKey());
        Component building = Component.translatable(free.building().getBuildingType().getTranslationKey());
        return new Result.Proposal(new Order("take_job", citizen.getName() + " works as "
                + free.module().getJobDisplayName() + " at " + describe(free),
                Component.translatable("livingcolonists.order.job.ask", job, building), player -> {
            if (free.module().isFull()) {
                return new Outcome(false, Component.translatable("livingcolonists.order.job.taken", job), null);
            }
            if (old != null) {
                old.removeCitizen(citizen);
            }
            free.module().setHiringMode(HiringMode.MANUAL);
            if (!free.module().assignCitizen(citizen)) {
                if (old != null) {
                    old.assignCitizen(citizen);
                }
                return new Outcome(false, Component.translatable("livingcolonists.order.job.refused", job), null);
            }
            free.building().markDirty();
            return new Outcome(true, Component.translatable("livingcolonists.order.job.done", job, building), () -> {
                free.module().removeCitizen(citizen);
                if (old != null) {
                    old.assignCitizen(citizen);
                }
            });
        }));
    }

    private static WorkerBuildingModule currentModule(ICitizenData citizen) {
        IBuilding work = citizen.getWorkBuilding();
        if (work == null) {
            return null;
        }
        return work.getModules(WorkerBuildingModule.class).stream()
                .filter(module -> module.hasAssignedCitizen(citizen)).findFirst().orElse(null);
    }

    /** Every position colonists can be hired for, named by its building and its profession. */
    private static List<ItemMatch.Candidate<Position>> positions(IColony colony, String locale) {
        List<ItemMatch.Candidate<Position>> positions = new ArrayList<>();
        for (ItemMatch.Candidate<IBuilding> building : UpgradeBuilding.candidates(colony, locale)) {
            for (WorkerBuildingModule module : building.value().getModules(WorkerBuildingModule.class)) {
                if (module.getJobEntry() == null) {
                    continue;
                }
                Set<String> names = new LinkedHashSet<>(building.names());
                names.addAll(JobNames.of(module.getJobEntry(), locale));
                positions.add(new ItemMatch.Candidate<>(new Position(building.value(), module),
                        module.getJobEntry().getKey().toString(), names));
            }
        }
        return positions;
    }

    private static String describe(Position position) {
        return "the " + Component.translatable(position.building().getBuildingType().getTranslationKey()).getString()
                + " (" + position.module().getJobDisplayName() + ", " + position.module().getAssignedCitizen().size()
                + " of " + position.module().getModuleMax() + " positions taken)";
    }
}
