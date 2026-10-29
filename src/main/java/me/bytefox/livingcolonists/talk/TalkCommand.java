package me.bytefox.livingcolonists.talk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /lc talk <message>}: say something to the colonist you look at, or the nearest one. */
public final class TalkCommand {
    private TalkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, TalkService talk) {
        dispatcher.register(Commands.literal("lc")
                .then(Commands.literal("talk")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> talk.talk(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "message"))))));
    }
}
