package me.bytefox.livingcolonists.debug;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.behaviour.Profile;
import me.bytefox.livingcolonists.llm.UsageStats;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Promise;
import me.bytefox.livingcolonists.bonds.Relation;
import me.bytefox.livingcolonists.memory.Fact;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.personality.Archetype;
import me.bytefox.livingcolonists.personality.Personality;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.talk.Conversations;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /lcdebug ...}: tools for server operators, and for single-player worlds with cheats on
 * (permission level 2, like {@code /gamemode}). Each subsystem adds its subcommands here.
 * Colonist subcommands act on the colonist named at the end of the command (in the colony you
 * stand in), or else on your conversation partner, or else on the one you look at or the nearest.
 */
public final class DebugCommand {
    /** Operators only. */
    private static final int PERMISSION_LEVEL = 2;
    /** How far the looked-at colonist may be, in blocks. */
    private static final double LOOK_RANGE = 16.0;

    private static Conversations conversations;
    private static PerceptionService perception;

    private DebugCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, UsageStats stats,
                                PersonalityService personalities, PromptLibrary prompts, TalkService talk,
                                Conversations conversations, PerceptionService perception, MemoryService memory,
                                BondsService bonds) {
        DebugCommand.conversations = conversations;
        DebugCommand.perception = perception;
        dispatcher.register(Commands.literal("lcdebug")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("stats").executes(context -> showStats(context.getSource(), stats)))
                .then(profileCommand())
                .then(withColonist(Commands.literal("colonist"),
                        (context, citizen) -> showColonist(context.getSource(), personalities, citizen)))
                .then(Commands.literal("archetype")
                        .then(withColonist(Commands.literal("reroll"),
                                (context, citizen) -> setArchetype(context.getSource(), personalities, citizen, null)))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        personalities.archetypes().all().stream().map(Archetype::id), builder))
                                .executes(context -> setArchetype(context, personalities, colonist(context, null)))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> setArchetype(context, personalities,
                                                colonist(context, StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("bio").then(withColonist(Commands.literal("regenerate"),
                        (context, citizen) -> regenerateBio(context.getSource(), personalities, citizen))))
                .then(withColonist(Commands.literal("prompt"),
                        (context, citizen) -> showPrompt(context.getSource(), talk, citizen)))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource(), personalities, prompts)))
                .then(Commands.literal("memory")
                        .then(withColonist(Commands.literal("show"),
                                (context, citizen) -> showMemory(context.getSource(), memory, citizen)))
                        .then(withColonist(Commands.literal("clear"),
                                (context, citizen) -> clearMemory(context.getSource(), memory, citizen)))
                        .then(Commands.literal("digest").executes(context -> digest(context.getSource(), memory))))
                .then(Commands.literal("bonds")
                        .then(withColonist(Commands.literal("show"),
                                (context, citizen) -> showBonds(context.getSource(), bonds, citizen)))
                        .then(withColonist(Commands.literal("clear"),
                                (context, citizen) -> clearBonds(context.getSource(), bonds, citizen))))
                .then(withColonist(Commands.literal("relations"),
                        (context, citizen) -> showRelations(context.getSource(), bonds, citizen))
                        .then(Commands.literal("all").executes(context -> showColonyRelations(context.getSource(), bonds))))
                .then(Commands.literal("orders").executes(context -> showOrders(context.getSource(), talk)))
                .then(Commands.literal("thread").executes(context -> showThread(context.getSource(), talk))
                        .then(Commands.literal("break").executes(context -> breakThread(context.getSource(), talk)))));
    }

    private interface ColonistAction {
        int run(CommandContext<CommandSourceStack> context, ICitizenData citizen) throws CommandSyntaxException;
    }

    /** Adds "act on the looked-at colonist" and "act on the named colonist" to a subcommand. */
    private static LiteralArgumentBuilder<CommandSourceStack> withColonist(LiteralArgumentBuilder<CommandSourceStack> node,
                                                                          ColonistAction action) {
        return node.executes(context -> action.run(context, colonist(context, null)))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> action.run(context, colonist(context, StringArgumentType.getString(context, "name")))));
    }

    private static CommandSyntaxException failure(String key, Object... args) {
        return new SimpleCommandExceptionType(Component.translatable(key, args)).create();
    }

    /** The colonist named in the colony the player stands in, or else the one they look at. */
    private static ICitizenData colonist(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (name == null) {
            Optional<ICitizenData> partner = conversations == null ? Optional.empty() : conversations.partner(player);
            if (partner.isPresent()) {
                return partner.get();
            }
            Optional<AbstractEntityCitizen> looked = TalkService.findListener(player, LOOK_RANGE, false);
            if (looked.isPresent()) {
                return looked.get().getCitizenData();
            }
            throw failure("livingcolonists.debug.colonist.none");
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (colony == null) {
            throw failure("livingcolonists.colony.outside");
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        List<ICitizenData> matches = colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(wanted)).toList();
        Optional<ICitizenData> exact = matches.stream().filter(c -> c.getName().equalsIgnoreCase(name.strip())).findFirst();
        if (exact.isPresent() || matches.size() == 1) {
            return exact.orElse(matches.get(0));
        }
        throw failure(matches.isEmpty() ? "livingcolonists.debug.colonist.not_found"
                : "livingcolonists.debug.colonist.ambiguous", name);
    }

    private static int showColonist(CommandSourceStack source, PersonalityService personalities, ICitizenData citizen) {
        Personality personality = personalities.personality(citizen, source.getServer()).orElse(personalities.get(citizen));
        String skills = PersonalityService.skills(citizen).entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.colonist.title", citizen.getName(),
                citizen.getColony().getName(), Component.translatable(citizen.isChild()
                        ? "livingcolonists.debug.colonist.child" : "livingcolonists.debug.colonist.adult")), false);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.colonist.archetype",
                personality == null ? "-" : personality.archetype()), false);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.colonist.bio",
                personality == null || personality.bio().isEmpty() ? "-" : personality.bio()), false);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.colonist.skills", skills), false);
        if (perception != null) {
            String situation = perception.situation(citizen);
            source.sendSuccess(() -> Component.literal(situation.isEmpty() ? "-" : situation), false);
        }
        return 1;
    }

    private static int setArchetype(CommandContext<CommandSourceStack> context, PersonalityService personalities,
                                    ICitizenData citizen) {
        return setArchetype(context.getSource(), personalities, citizen, StringArgumentType.getString(context, "id"));
    }

    private static int setArchetype(CommandSourceStack source, PersonalityService personalities, ICitizenData citizen,
                                    String id) {
        if (id != null && personalities.archetypes().find(id).isEmpty()) {
            source.sendFailure(Component.translatable("livingcolonists.debug.archetype.unknown", id));
            return 0;
        }
        Personality personality = personalities.setArchetype(citizen, id);
        if (personality == null) {
            source.sendFailure(Component.translatable("livingcolonists.debug.world_not_ready"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.archetype.set", citizen.getName(),
                personality.archetype()), true);
        return 1;
    }

    private static int regenerateBio(CommandSourceStack source, PersonalityService personalities, ICitizenData citizen) {
        personalities.personality(citizen, source.getServer());
        if (!personalities.writeBio(citizen, source.getServer(), true)) {
            source.sendFailure(Component.translatable("livingcolonists.debug.bio.busy", citizen.getName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.bio.requested", citizen.getName()), true);
        return 1;
    }

    private static int showPrompt(CommandSourceStack source, TalkService talk, ICitizenData citizen) {
        Optional<String> prompt = talk.lastPrompt(citizen);
        if (prompt.isEmpty()) {
            source.sendFailure(Component.translatable("livingcolonists.debug.prompt.none", citizen.getName()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(prompt.get()), false);
        return 1;
    }

    private static int showMemory(CommandSourceStack source, MemoryService memory, ICitizenData citizen) {
        Map<String, List<MemoryLine>> talks = memory.privateTalks(citizen);
        List<Fact> facts = memory.facts(citizen);
        if (talks.isEmpty() && facts.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.memory.empty", citizen.getName()), false);
            return 0;
        }
        if (!facts.isEmpty()) {
            long now = citizen.getColony().getWorld().getGameTime();
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.memory.facts", citizen.getName(),
                    facts.size()), false);
            for (Fact fact : facts) {
                String line = "  [" + fact.importance() + "] " + fact.text() + " (" + (fact.isPrivate()
                        ? "from " + fact.teller() : "colony chat") + ", " + (fact.expires() - now) / 24000 + "d left"
                        + (fact.told() ? ", told" : "") + ")";
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        talks.forEach((player, lines) -> {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.memory.talk", citizen.getName(),
                    player, lines.size()), false);
            for (MemoryLine line : lines) {
                source.sendSuccess(() -> Component.literal("  <" + line.speaker() + "> " + line.text()), false);
            }
        });
        return talks.size() + facts.size();
    }

    private static int showBonds(CommandSourceStack source, BondsService bonds, ICitizenData citizen) {
        List<Promise> promises = bonds.promises(citizen);
        Map<String, Relation> relations = bonds.relations(citizen);
        if (promises.isEmpty() && relations.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.bonds.empty", citizen.getName()), false);
            return 0;
        }
        long now = citizen.getColony().getWorld().getGameTime();
        for (Promise promise : promises) {
            String due = promise.due() == 0 ? "no deadline" : "due in " + (promise.due() - now) / 24000 + "d";
            String line = "  [" + promise.status() + ", " + promise.about().key() + ", " + due + "] " + promise.makerName()
                    + ": " + promise.text();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        relations.forEach((player, relation) -> {
            String line = "  " + player + ": rapport " + relation.rapport() + relation.notes().stream()
                    .map(note -> (note.kind() ? " +" : " -") + note.text()).reduce("", String::concat);
            source.sendSuccess(() -> Component.literal(line), false);
        });
        return promises.size() + relations.size();
    }

    private static int clearBonds(CommandSourceStack source, BondsService bonds, ICitizenData citizen) {
        int dropped = bonds.forget(citizen);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.bonds.cleared", citizen.getName(), dropped),
                true);
        return dropped;
    }

    private static int digest(CommandSourceStack source, MemoryService memory) {
        int started = memory.digest(source.getServer(), true);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.memory.digest", started), true);
        return started;
    }

    private static int clearMemory(CommandSourceStack source, MemoryService memory, ICitizenData citizen) {
        int dropped = memory.forget(citizen);
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.memory.cleared", citizen.getName(), dropped),
                true);
        return dropped;
    }

    private static int showRelations(CommandSourceStack source, BondsService bonds, ICitizenData citizen) {
        Map<String, Relation> relations = bonds.colonistRelations(citizen);
        if (relations.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.relations.empty", citizen.getName()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.relations.title", citizen.getName()), false);
        relations.forEach((other, relation) -> source.sendSuccess(() -> Component.literal(relationLine(other, relation)), false));
        return relations.size();
    }

    private static int showColonyRelations(CommandSourceStack source, BondsService bonds) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (colony == null) {
            throw failure("livingcolonists.colony.outside");
        }
        Map<String, Relation> relations = bonds.colonyRelations(colony);
        if (relations.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.relations.empty", colony.getName()), false);
            return 0;
        }
        relations.forEach((pair, relation) -> source.sendSuccess(() -> Component.literal(relationLine(pair, relation)), false));
        return relations.size();
    }

    /** "  Anna: rapport 14 + backed her up; - snapped at her". */
    private static String relationLine(String who, Relation relation) {
        StringBuilder line = new StringBuilder("  ").append(who).append(": rapport ").append(relation.rapport());
        for (Relation.Note note : relation.notes()) {
            line.append(note.kind() ? "\n    + " : "\n    - ").append(note.text());
        }
        return line.toString();
    }

    private static int showOrders(CommandSourceStack source, TalkService talk) {
        List<String> log = talk.errands().log();
        if (log.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.orders.none"), false);
        }
        for (String line : log) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return log.size();
    }

    private static int showThread(CommandSourceStack source, TalkService talk) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (colony == null) {
            throw failure("livingcolonists.colony.outside");
        }
        source.sendSuccess(() -> Component.literal(talk.threads().state(colony)), false);
        for (MemoryLine line : talk.threads().transcript(colony)) {
            source.sendSuccess(() -> Component.literal("  <" + line.speaker() + "> " + line.text()), false);
        }
        return 1;
    }

    private static int breakThread(CommandSourceStack source, TalkService talk) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (colony == null) {
            throw failure("livingcolonists.colony.outside");
        }
        boolean stopped = talk.threads().stop(colony);
        source.sendSuccess(() -> Component.translatable(stopped ? "livingcolonists.debug.thread.stopped"
                : "livingcolonists.debug.thread.none"), true);
        return stopped ? 1 : 0;
    }

    private static int reload(CommandSourceStack source, PersonalityService personalities, PromptLibrary prompts) {
        personalities.archetypes().reload();
        prompts.reload();
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.reload",
                personalities.archetypes().all().size(), prompts.size()), true);
        return 1;
    }

    /** {@code /lcdebug profile [minimal|light|default|immersive]}: show or change the profile. */
    private static LiteralArgumentBuilder<CommandSourceStack> profileCommand() {
        var command = Commands.literal("profile").executes(context -> showProfile(context.getSource()));
        for (Profile profile : Profile.values()) {
            command.then(Commands.literal(profile.name().toLowerCase(Locale.ROOT)).executes(context -> {
                Config.PROFILE.set(profile);
                Config.PROFILE.save();
                context.getSource().sendSuccess(() -> Component.translatable("livingcolonists.debug.profile.set",
                        profile.name()), true);
                return showProfile(context.getSource());
            }));
        }
        return command;
    }

    private static int showProfile(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.profile.current",
                Config.PROFILE.get().name(), level(Module.PERSONALITY), level(Module.PERCEPTION), level(Module.MEMORY)), false);
        return 1;
    }

    private static String level(Module module) {
        return Config.level(module).name().toLowerCase(Locale.ROOT);
    }

    private static int showStats(CommandSourceStack source, UsageStats stats) {
        Map<String, UsageStats.Counts> session = stats.session();
        Map<String, UsageStats.Counts> total = stats.total();
        if (total.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.stats.empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("livingcolonists.debug.stats.title"), false);
        for (String model : new TreeSet<>(total.keySet())) {
            UsageStats.Counts now = session.getOrDefault(model, UsageStats.Counts.ZERO);
            UsageStats.Counts all = total.get(model);
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.stats.model", model), false);
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.stats.session", counts(now)), false);
            source.sendSuccess(() -> Component.translatable("livingcolonists.debug.stats.total", counts(all)), false);
        }
        return total.size();
    }

    private static Component counts(UsageStats.Counts counts) {
        return Component.translatable("livingcolonists.debug.stats.counts",
                counts.requests(), counts.failures(), counts.promptTokens(), counts.cachedTokens(),
                counts.completionTokens(), String.format(Locale.ROOT, "%.1f", counts.averageLatencyMillis() / 1000.0));
    }
}
