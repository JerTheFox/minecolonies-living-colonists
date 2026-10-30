package me.bytefox.livingcolonists.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import me.bytefox.livingcolonists.talk.ChatMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import me.bytefox.livingcolonists.client.settings.SettingsScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client side: the keys and the chat indicator. Never loaded on a dedicated server. */
@Mod(value = LivingColonists.MODID, dist = Dist.CLIENT)
public final class LivingColonistsClient {
    private static final String CATEGORY = "key.categories.livingcolonists";

    /** Y, next to T for player chat: write to the colony. */
    private static final KeyMapping COLONY = new KeyMapping("key.livingcolonists.colony", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
    /** U: a private talk with the colonist you look at, with the chat open. */
    private static final KeyMapping TALK = new KeyMapping("key.livingcolonists.talk", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);
    /** Unbound by default: pick the colonist you look at as your partner without opening the chat. */
    private static final KeyMapping FOCUS = new KeyMapping("key.livingcolonists.focus", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, CATEGORY);
    /** Unbound by default: players ↔ colony. */
    private static final KeyMapping MODE = new KeyMapping("key.livingcolonists.mode", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, CATEGORY);

    /** Unbound by default: the mod's settings (also in the mod list). */
    private static final KeyMapping SETTINGS = new KeyMapping("key.livingcolonists.settings", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, CATEGORY);

    public LivingColonistsClient(IEventBus modEventBus, ModContainer container) {
        // The mod list's "Config" button (NeoForge, Catalogue and the like) opens our own screen.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(COLONY);
            event.register(TALK);
            event.register(FOCUS);
            event.register(MODE);
            event.register(SETTINGS);
        });
        NeoForge.EVENT_BUS.addListener(LivingColonistsClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(LivingColonistsClient::onKey);
        NeoForge.EVENT_BUS.addListener(ConversationIndicator::render);
        NeoForge.EVENT_BUS.addListener(ConversationIndicator::click);
        NeoForge.EVENT_BUS.addListener(TypingIndicator::renderHud);
        NeoForge.EVENT_BUS.addListener(SpeechBubbles::render);
        NeoForge.EVENT_BUS.addListener(SpeechBubbles::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientConversation.reset();
            TypingIndicator.reset();
            SpeechBubbles.clear();
        });
    }

    /**
     * The vanilla chat key (T) means "talk to players": it leaves colony mode or a private talk.
     * The command key (/) is left alone.
     */
    private static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() == GLFW.GLFW_PRESS && minecraft.screen == null && minecraft.player != null
                && minecraft.options.keyChat.matches(event.getKey(), event.getScanCode())) {
            ConversationState state = ClientConversation.state();
            if (state.hasPartner() || state.mode() != ChatMode.PLAYERS) {
                ClientConversation.send(ConversationAction.Action.MODE_PLAYERS);
            }
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (COLONY.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.CHAT_COLONY);
        }
        while (TALK.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.TALK);
        }
        while (FOCUS.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.FOCUS);
        }
        while (SETTINGS.consumeClick()) {
            Minecraft.getInstance().setScreen(new SettingsScreen(Minecraft.getInstance().screen));
        }
        while (MODE.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.CYCLE_MODE);
        }
    }
}
