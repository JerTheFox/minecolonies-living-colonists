package me.bytefox.livingcolonists.debug;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.behaviour.Profile;
import me.bytefox.livingcolonists.llm.UsageStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /lcdebug ...}: tools for server operators. Each subsystem adds its subcommands here. */
public final class DebugCommand {
    /** Operators only. */
    private static final int PERMISSION_LEVEL = 2;

    private DebugCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, UsageStats stats) {
        dispatcher.register(Commands.literal("lcdebug")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("stats").executes(context -> showStats(context.getSource(), stats)))
                .then(profileCommand()));
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
