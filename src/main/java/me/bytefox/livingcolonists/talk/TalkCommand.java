package me.bytefox.livingcolonists.talk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * What the chat's clickable parts run: the answers to a colonist's order question ({@code /lc yes},
 * {@code /lc no}, {@code /lc undo}), and a private talk with a colonist whose name was clicked
 * ({@code /lc lock <colony> <citizen>}; without them, with the one looked at).
 */
public final class TalkCommand {
    private static final int HELP_LINES = 5;
    private static final int HELP_LINES_NO_MODEL = 4;

    private TalkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, TalkService talk,
                                Conversations conversations) {
        dispatcher.register(Commands.literal("lc")
                .then(Commands.literal("lock")
                        .executes(context -> {
                            conversations.pressTalk(context.getSource().getPlayerOrException(), false);
                            return 1;
                        })
                        .then(Commands.argument("colony", IntegerArgumentType.integer(0))
                                .then(Commands.argument("citizen", IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            conversations.lock(context.getSource().getPlayerOrException(),
                                                    IntegerArgumentType.getInteger(context, "colony"),
                                                    IntegerArgumentType.getInteger(context, "citizen"));
                                            return 1;
                                        }))))
                .then(Commands.literal("yes").executes(context ->
                        talk.errands().confirm(context.getSource().getPlayerOrException())))
                .then(Commands.literal("no").executes(context ->
                        talk.errands().cancel(context.getSource().getPlayerOrException())))
                .then(Commands.literal("undo").executes(context ->
                        talk.errands().undo(context.getSource().getPlayerOrException())))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("menu").then(Commands.argument("colony", IntegerArgumentType.integer(0))
                        .then(Commands.argument("citizen", IntegerArgumentType.integer(0))
                                .then(Commands.argument("option", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> menu(context, talk))))))
                .then(Commands.literal("promises").executes(context -> {
                    net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayerOrException();
                    com.minecolonies.api.colony.IColony colony = me.bytefox.livingcolonists.colony.Colonies.at(player);
                    if (colony == null) {
                        context.getSource().sendFailure(net.minecraft.network.chat.Component.translatable("livingcolonists.colony.outside"));
                        return 0;
                    }
                    return talk.bonds().list(player, colony);
                }))
                .then(Commands.literal("promise")
                        .then(Commands.literal("yes").then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                .executes(context -> talk.bonds().answer(context.getSource().getPlayerOrException(),
                                        com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "id"), true))))
                        .then(Commands.literal("no").then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                .executes(context -> talk.bonds().answer(context.getSource().getPlayerOrException(),
                                        com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "id"), false)))))
                .then(Commands.literal("colony")
                        .then(Commands.literal("off").executes(context -> colonyOff(context.getSource(), talk, true)))
                        .then(Commands.literal("on").executes(context -> colonyOff(context.getSource(), talk, false)))));
    }

    /** The colony's owner turns the mod off or on for the colony they stand in (#55). */
    private static int colonyOff(net.minecraft.commands.CommandSourceStack source, TalkService talk, boolean off)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        com.minecolonies.api.colony.IColony colony = me.bytefox.livingcolonists.colony.Colonies.at(player);
        if (colony == null) {
            source.sendFailure(net.minecraft.network.chat.Component.translatable("livingcolonists.colony.outside"));
            return 0;
        }
        if (me.bytefox.livingcolonists.colony.Colonies.role(player, colony)
                != me.bytefox.livingcolonists.behaviour.ColonyAccess.Role.OWNER) {
            source.sendFailure(net.minecraft.network.chat.Component.translatable("livingcolonists.colony.owner_only",
                    colony.getName()));
            return 0;
        }
        talk.setColonyOff(colony, off);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.translatable(
                off ? "livingcolonists.colony.off" : "livingcolonists.colony.on", colony.getName()), true);
        return 1;
    }

    /** What players can do with colonists, in a few lines (#48). */
    /** A choice in the talk menu (#68): the colonist must still be there and the player inside the colony. */
    private static int menu(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, TalkService talk)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var citizen = Audience.resolve(player.level().dimension(), IntegerArgumentType.getInteger(context, "colony"),
                IntegerArgumentType.getInteger(context, "citizen"));
        if (citizen == null || !Audience.isInside(player, citizen.getColony()) || !Audience.mayTalk(player, citizen.getColony())) {
            return 0;
        }
        talk.menu().choose(player, citizen, com.mojang.brigadier.arguments.StringArgumentType.getString(context, "option"));
        return 1;
    }

    private static int help(net.minecraft.commands.CommandSourceStack source) {
        boolean model = me.bytefox.livingcolonists.Config.modelOn();
        for (int i = 1; i <= (model ? HELP_LINES : HELP_LINES_NO_MODEL); i++) {
            String key = (model ? "livingcolonists.help." : "livingcolonists.help.no_model.") + i;
            source.sendSuccess(() -> net.minecraft.network.chat.Component.translatable(key), false);
        }
        return 1;
    }
}
