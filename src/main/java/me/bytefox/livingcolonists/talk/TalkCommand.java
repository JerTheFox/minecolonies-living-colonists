package me.bytefox.livingcolonists.talk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Fallback commands for clients without the mod's keys:
 * {@code /lc talk <message>}, {@code /lc lock [colony citizen]}, {@code /lc mode [players|colony]};
 * and the answers to a colonist's order question: {@code /lc yes}, {@code /lc no}, {@code /lc undo}.
 * Clicking a colonist's name in chat runs {@code /lc lock <colony> <citizen>}.
 */
public final class TalkCommand {
    private TalkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, TalkService talk,
                                Conversations conversations) {
        dispatcher.register(Commands.literal("lc")
                .then(Commands.literal("talk")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> talk.talk(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "message")))))
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
                .then(Commands.literal("mode")
                        .then(Commands.literal("players").executes(context -> {
                            conversations.setMode(context.getSource().getPlayerOrException(), ChatMode.PLAYERS);
                            return 1;
                        }))
                        .then(Commands.literal("colony").executes(context -> {
                            conversations.setMode(context.getSource().getPlayerOrException(), ChatMode.COLONY);
                            return 1;
                        }))));
    }
}
