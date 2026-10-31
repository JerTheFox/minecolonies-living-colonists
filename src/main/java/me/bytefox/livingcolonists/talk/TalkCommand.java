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
    private static int help(net.minecraft.commands.CommandSourceStack source) {
        for (int i = 1; i <= HELP_LINES; i++) {
            String key = "livingcolonists.help." + i;
            source.sendSuccess(() -> net.minecraft.network.chat.Component.translatable(key), false);
        }
        return HELP_LINES;
    }
}
