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
                        talk.errands().undo(context.getSource().getPlayerOrException()))));
    }
}
