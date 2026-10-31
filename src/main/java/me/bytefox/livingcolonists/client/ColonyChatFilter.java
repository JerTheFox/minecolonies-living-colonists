package me.bytefox.livingcolonists.client;

import me.bytefox.livingcolonists.talk.ChatMode;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/**
 * Hides colonists' colony chat lines for a player who wants them as speech bubbles only (#58). Answers to the
 * player's own colony messages stay: a line within a minute of the player writing to the colony is kept.
 * Private lines and order questions are other kinds of message and are never hidden. Client only.
 */
public final class ColonyChatFilter {
    /** After writing to the colony, the player sees colonists' lines in the chat this long, in milliseconds. */
    private static final long ANSWERS_MILLIS = 60_000;
    private static volatile long wroteAt;

    private ColonyChatFilter() {
    }

    /** The player sent a chat message: in colony mode it is to the colony, and its answers stay in the chat. */
    public static void sent(ClientChatEvent event) {
        if (ClientConversation.state().mode() == ChatMode.COLONY) {
            wroteAt = System.currentTimeMillis();
        }
    }

    public static void received(ClientChatReceivedEvent.System event) {
        if (ClientConfig.COLONY_CHAT_LINES.get() || System.currentTimeMillis() - wroteAt < ANSWERS_MILLIS) {
            return;
        }
        if (colonistLine(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    /** A colonist's line in the colony chat: "<Name> text" whose name runs "/lc lock …" when clicked. */
    static boolean colonistLine(Component message) {
        if (!(message.getContents() instanceof TranslatableContents translatable)
                || !translatable.getKey().equals("chat.type.text") || translatable.getArgs().length < 1
                || !(translatable.getArgs()[0] instanceof Component name)) {
            return false;
        }
        ClickEvent click = name.getStyle().getClickEvent();
        return click != null && click.getValue().startsWith("/lc lock ");
    }
}
