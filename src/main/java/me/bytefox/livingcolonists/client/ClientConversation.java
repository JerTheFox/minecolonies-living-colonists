package me.bytefox.livingcolonists.client;

import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import me.bytefox.livingcolonists.talk.ChatMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The client's copy of its conversation state, and the way to ask the server to change it. */
public final class ClientConversation {
    private static final ConversationState NONE = new ConversationState(ChatMode.PLAYERS, "", "", false);

    private static volatile ConversationState state = NONE;

    private ClientConversation() {
    }

    public static ConversationState state() {
        return state;
    }

    /** A state update from the server. Client thread. */
    public static void apply(ConversationState update) {
        state = update;
        Minecraft minecraft = Minecraft.getInstance();
        if (update.openChat() && update.hasPartner() && minecraft.screen == null) {
            minecraft.setScreen(new ChatScreen(""));
        }
    }

    public static void reset() {
        state = NONE;
    }

    public static void send(ConversationAction.Action action) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) {
            return;
        }
        if (connection.hasChannel(ConversationAction.TYPE)) {
            PacketDistributor.sendToServer(new ConversationAction(action));
        } else {
            minecraft.player.displayClientMessage(Component.translatable("livingcolonists.client.no_server_mod"), true);
        }
    }
}
