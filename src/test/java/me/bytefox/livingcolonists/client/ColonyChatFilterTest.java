package me.bytefox.livingcolonists.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.talk.ChatSpeechOutput;
import me.bytefox.livingcolonists.talk.Speaker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

/** The bubbles-only option (#58) hides colonists' colony lines as the server really sends them, and nothing else. */
class ColonyChatFilterTest {
    @Test
    void onlyColonistsLinesInTheColonyChatAreHidden() {
        List<Component> sent = new ArrayList<>();
        ServerPlayer player = mock(ServerPlayer.class, RETURNS_DEEP_STUBS);
        when(player.getDisplayName()).thenReturn(Component.literal("Alex"));
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(player).sendSystemMessage(any(Component.class));
        Speaker darcy = new Speaker("Darcy Abbott", "baker", "com.minecolonies.job.baker", "Blackwood", 1, 7, net.minecraft.ChatFormatting.GOLD);
        ChatSpeechOutput output = new ChatSpeechOutput();

        output.colonistSaid(darcy, "Хлеба на два дня.", List.of(player));
        output.playerSaid(player, "Blackwood", "сколько хлеба?", List.of(player));
        output.colonistWhispered(darcy, player, "Только тебе скажу.");

        assertTrue(ColonyChatFilter.colonistLine(sent.get(0)), "a colonist's colony line wasn't recognised");
        assertFalse(ColonyChatFilter.colonistLine(sent.get(1)), "the player's own line would be hidden");
        assertFalse(ColonyChatFilter.colonistLine(sent.get(2)), "a private line would be hidden");
    }
}
