/*
 * Based on the walking tools of Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3, src/me/lovkar/errands/tools/
 * (ComeHereAction, FollowPlayerAction, WaitHereAction, StopErrandAction, SendToBuildingAction,
 * CallCitizenAction, GatherAtAction, DismissAction, EveryoneHomeAction, FindCitizenAction).
 * Changed: names, buildings and professions are found by code; every walk has a time limit.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.talk.Addressing;
import me.bytefox.livingcolonists.talk.JobNames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Walking on the player's word. These are orders (owner and officers, the player's own words) but
 * happen at once, without a yes: "stop" ends them, and each runs out after a few minutes anyway.
 */
public final class WalkErrands {
    private static final int MAX_GATHERED = 30;

    private WalkErrands() {
    }

    public static List<Errand> all(Walks walks) {
        return List.of(
                command("come_here", "Walk over to the player who asked (\"come here\", \"come to me\", also when they call "
                        + "several of you at once) and wait by them a while. You find them yourself: no need to know or ask "
                        + "where they are.",
                        Map.of(), (c, a) -> self(walks, c, Walks.Kind.TO_PLAYER, null, 5, 2, "to the player")),
                command("follow_me", "Follow the player around (\"follow me\") for up to ten minutes.",
                        Map.of(), (c, a) -> self(walks, c, Walks.Kind.FOLLOW, null, 10, 0, "after the player")),
                command("wait_here", "Stay where you are (\"wait here\") for a few minutes.",
                        Map.of(), (c, a) -> self(walks, c, Walks.Kind.WAIT, null, 5, 0, "on the spot")),
                command("stop_walking", "Stop walking, following or waiting and go back to your work (\"stop\", "
                        + "\"you can go\"); with everyone, all colonists the player sent off go back.",
                        Map.of("who", "me, or everyone"), (c, a) -> stop(walks, c, Errand.string(a, "who"))),
                command("go_to", "Walk to a building of the colony (\"go to the hospital\") and stay there a minute.",
                        Map.of("building", "The building in plain English words, like hospital or warehouse"),
                        (c, a) -> goTo(walks, c, Errand.string(a, "building"))),
                command("call_colonist", "Call another colonist over to the player (\"call Idris here\").",
                        Map.of("name", "Who, as the colony spells the name"),
                        (c, a) -> call(walks, c, Errand.string(a, "name"))),
                command("gather", "Gather colonists (everyone, the unemployed, or one profession) here or at a building "
                        + "(\"everyone to the town hall\", \"the unemployed, come to me\"); they wait there a few minutes.",
                        Map.of("who", "everyone, unemployed, or a profession in plain English words",
                                "where", "here, or a building in plain English words"),
                        (c, a) -> gather(walks, c, Errand.string(a, "who"), Errand.string(a, "where"))),
                command("everyone_home", "Send every colonist but the guards home (\"everyone go home\").",
                        Map.of(), (c, a) -> home(walks, c)),
                command("lead_me", "Lead the player to a building or a colonist (\"take me to the bakery\"), "
                        + "waiting when they fall behind.",
                        Map.of("to", "A building in plain English words, or a colonist's name"),
                        (c, a) -> lead(walks, c, Errand.string(a, "to"))));
    }

    private interface Action {
        JsonObject run(Errand.Context context, JsonObject arguments);
    }

    private static Errand command(String name, String description, Map<String, String> parameters, Action action) {
        ToolSpec spec = Errands.commandSpec(name, description, parameters);
        return new Errand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public boolean isOrder() {
                return true;
            }

            @Override
            public Result run(Context context, JsonObject arguments) {
                return new Result.Answer(action.run(context, arguments));
            }
        };
    }

    private static JsonObject ok(String info) {
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("info", info);
        return reply;
    }

    private static JsonObject self(Walks walks, Errand.Context context, Walks.Kind kind, BlockPos target, int minutes,
                                   int holdMinutes, String what) {
        ServerPlayer player = context.player();
        if (!walks.start(player.server, context.citizen(), player.getUUID(), kind, target, minutes * Walks.MINUTE,
                holdMinutes * Walks.MINUTE, what)) {
            return Errand.error("You are too far away to do that right now.");
        }
        return ok("You do it now. It lasts a few minutes, or until the player says stop; then you go back to work.");
    }

    private static JsonObject stop(Walks walks, Errand.Context context, String who) {
        if (who.toLowerCase().startsWith("every") || who.toLowerCase().startsWith("all")) {
            int stopped = walks.stopAll(context.player().getUUID());
            return ok(stopped == 0 ? "Nobody was on an errand for the player." : stopped + " colonists go back to work.");
        }
        return walks.stop(context.citizen()) ? ok("You go back to your work.") : ok("You weren't on an errand anyway.");
    }

    private static IBuilding building(IColony colony, String words, ServerPlayer player) {
        return ItemMatch.find(words, UpgradeBuilding.candidates(colony, player.clientInformation().language()))
                instanceof ItemMatch.Result.Found<IBuilding> found ? found.value() : null;
    }

    private static JsonObject goTo(Walks walks, Errand.Context context, String words) {
        IBuilding building = building(context.colony(), words, context.player());
        if (building == null) {
            return Errand.notFound("single building in the colony", words);
        }
        return self(walks, context, Walks.Kind.TO_POS, building.getPosition(), 5, 1,
                "to the " + ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey())));
    }

    private static ICitizenData colonist(IColony colony, String name) {
        List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
        List<Integer> named = Addressing.allMentioned(name, citizens.stream().map(ICitizenData::getName).toList());
        return named.isEmpty() ? null : citizens.get(named.get(0));
    }

    private static JsonObject call(Walks walks, Errand.Context context, String name) {
        ICitizenData other = colonist(context.colony(), name);
        if (other == null) {
            return Errand.error("Nobody in the colony is called \"" + name + "\".");
        }
        ServerPlayer player = context.player();
        if (!walks.start(player.server, other, player.getUUID(), Walks.Kind.TO_PLAYER, null, 5 * Walks.MINUTE,
                2 * Walks.MINUTE, "called by " + context.citizen().getName())) {
            return Errand.error(other.getName() + " is too far away to hear the call.");
        }
        return ok(other.getName() + " is on the way to the player.");
    }

    private static JsonObject gather(Walks walks, Errand.Context context, String who, String where) {
        IColony colony = context.colony();
        ServerPlayer player = context.player();
        boolean here = where.isEmpty() || where.equalsIgnoreCase("here");
        IBuilding building = here ? null : building(colony, where, player);
        if (!here && building == null) {
            return Errand.notFound("single building in the colony", where);
        }
        List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
        List<ICitizenData> chosen = new ArrayList<>();
        if (who.isEmpty() || who.toLowerCase().startsWith("every") || who.toLowerCase().startsWith("all")) {
            chosen.addAll(citizens);
        } else if (who.toLowerCase().matches(".*(unemploy|jobless|without (a )?job|idle|безработ|без работы).*")) {
            citizens.stream().filter(c -> c.getJob() == null && !c.isChild()).forEach(chosen::add);
            if (chosen.isEmpty()) {
                return Errand.error("Everyone in the colony has a job.");
            }
        } else {
            String locale = player.clientInformation().language();
            JobNames jobs = new JobNames();
            Addressing.byJob(who, citizens.stream().map(c -> jobs.of(c, locale)).toList()).forEach(i -> chosen.add(citizens.get(i)));
            if (chosen.isEmpty()) {
                return Errand.error("Nobody in the colony works as \"" + who + "\".");
            }
        }
        int going = 0;
        for (ICitizenData citizen : chosen.subList(0, Math.min(MAX_GATHERED, chosen.size()))) {
            boolean started = here
                    ? walks.start(player.server, citizen, player.getUUID(), Walks.Kind.TO_PLAYER, null, 5 * Walks.MINUTE,
                    3 * Walks.MINUTE, "gathering")
                    : walks.start(player.server, citizen, player.getUUID(), Walks.Kind.TO_POS, building.getPosition(),
                    5 * Walks.MINUTE, 3 * Walks.MINUTE, "gathering");
            going += started ? 1 : 0;
        }
        return ok(going + " colonists are on their way" + (going < chosen.size() ? "; the rest are too far away." : "."));
    }

    private static JsonObject home(Walks walks, Errand.Context context) {
        ServerPlayer player = context.player();
        MinecraftServer server = player.server;
        int going = 0;
        for (ICitizenData citizen : context.colony().getCitizenManager().getCitizens()) {
            IBuilding home = citizen.getHomeBuilding();
            if (citizen.getJob() != null && citizen.getJob().isGuard()) {
                continue;
            }
            if (home != null && walks.start(server, citizen, player.getUUID(), Walks.Kind.TO_POS, home.getPosition(),
                    5 * Walks.MINUTE, Walks.MINUTE, "home")) {
                going++;
            }
        }
        return ok(going + " colonists go home.");
    }

    private static JsonObject lead(Walks walks, Errand.Context context, String to) {
        ICitizenData other = colonist(context.colony(), to);
        BlockPos target;
        String what;
        if (other != null && other.getEntity().isPresent()) {
            target = other.getEntity().get().blockPosition();
            what = "to " + other.getName();
        } else {
            IBuilding building = building(context.colony(), to, context.player());
            if (building == null) {
                return Errand.error("There is nobody and no single building called \"" + to + "\" to lead the player to.");
            }
            target = building.getPosition();
            what = "to the " + ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey()));
        }
        return self(walks, context, Walks.Kind.LEAD, target, 5, 0, what);
    }
}
