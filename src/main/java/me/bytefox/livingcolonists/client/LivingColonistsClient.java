package me.bytefox.livingcolonists.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.network.ConversationAction;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client side: the two keys and the chat indicator. Never loaded on a dedicated server. */
@Mod(value = LivingColonists.MODID, dist = Dist.CLIENT)
public final class LivingColonistsClient {
    private static final String CATEGORY = "key.categories.livingcolonists";

    /** Y, next to T for chat: lock the colonist you look at and open the chat. */
    private static final KeyMapping TALK = new KeyMapping("key.livingcolonists.talk", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
    /** H: players ↔ whole colony. */
    private static final KeyMapping MODE = new KeyMapping("key.livingcolonists.mode", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);

    public LivingColonistsClient(IEventBus modEventBus) {
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(TALK);
            event.register(MODE);
        });
        NeoForge.EVENT_BUS.addListener(LivingColonistsClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ConversationIndicator::render);
        NeoForge.EVENT_BUS.addListener(ConversationIndicator::click);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientConversation.reset());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (TALK.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.TALK);
        }
        while (MODE.consumeClick()) {
            ClientConversation.send(ConversationAction.Action.CYCLE_MODE);
        }
    }
}
