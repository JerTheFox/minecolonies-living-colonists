package me.bytefox.livingcolonists.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** What each player chooses for themselves, on their own client (#58). */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue COLONY_CHAT_LINES = BUILDER
            .translation("livingcolonists.configuration.colonyChatLines")
            .comment("On: colonists' lines in the colony chat show in the chat and as speech bubbles. Off: as speech bubbles",
                    "only; answers to your own messages, your private talks and order questions stay in the chat. Default on.")
            .define("colonyChatLines", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}
