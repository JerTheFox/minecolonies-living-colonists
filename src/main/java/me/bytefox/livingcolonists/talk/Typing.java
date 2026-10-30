package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.network.TypingState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "Idris is typing…" for the players who will see the line. It shows once the colonist has had
 * time to read what was said, not at once, and goes away when the line is out or dropped.
 * Server thread only.
 */
public final class Typing {
    private record Entry(Speaker speaker, List<UUID> audience, long startTick, boolean shown) {
    }

    private final Map<Long, Entry> typing = new HashMap<>();

    /** The colonist will be typing for {@code audience} from {@code startTick} on. */
    public void start(MinecraftServer server, Speaker speaker, Collection<ServerPlayer> audience, long startTick) {
        long id = TalkService.key(speaker.colonyId(), speaker.citizenId());
        stop(server, id);
        typing.put(id, new Entry(speaker, audience.stream().map(ServerPlayer::getUUID).toList(), startTick, false));
    }

    /** The colonist stopped: the line is out, or they chose silence, or the line was dropped. */
    public void stop(MinecraftServer server, Speaker speaker) {
        stop(server, TalkService.key(speaker.colonyId(), speaker.citizenId()));
    }

    private void stop(MinecraftServer server, long id) {
        Entry entry = typing.remove(id);
        if (entry != null && entry.shown()) {
            send(server, entry, false);
        }
    }

    /** Everyone typing in this colony stops: its conversation ended. */
    public void stopColony(MinecraftServer server, int colonyId) {
        for (Long id : List.copyOf(typing.keySet())) {
            if ((int) (id >> 32) == colonyId) {
                stop(server, id);
            }
        }
    }

    /** Shows typing whose reading pause is over. Server tick. */
    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (Iterator<Map.Entry<Long, Entry>> it = typing.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Entry> e = it.next();
            Entry entry = e.getValue();
            if (!entry.shown() && entry.startTick() <= now) {
                Entry shown = new Entry(entry.speaker(), entry.audience(), entry.startTick(), true);
                e.setValue(shown);
                send(server, shown, true);
            }
        }
    }

    public void clear() {
        typing.clear();
    }

    private static void send(MinecraftServer server, Entry entry, boolean on) {
        Speaker speaker = entry.speaker();
        TypingState state = new TypingState(TalkService.key(speaker.colonyId(), speaker.citizenId()), speaker.name(),
                speaker.color() == null ? -1 : speaker.color().getId(), on);
        List<ServerPlayer> players = new ArrayList<>();
        for (UUID id : entry.audience()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                players.add(player);
                PacketDistributor.sendToPlayer(player, state);
            }
        }
        if (Config.SPEECH_BUBBLES.get()) {
            Bubbles.colonist(speaker.colonyId(), speaker.citizenId()).ifPresent(entity -> Bubbles.typing(entity, on, players));
        }
    }
}
