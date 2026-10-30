package me.bytefox.livingcolonists.talk;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-player conversation state on the server: the chat mode and the colonist picked for a
 * private talk. A private talk works like a direct message: no distance limit and the colonist
 * keeps doing their job, but it only lasts while the player is inside that colony's borders.
 *
 * <p>Everything except {@link #wantsChat} runs on the server thread. {@code wantsChat} is called
 * from the chat event, which may come from a network thread, so the map is concurrent and the
 * fields volatile.</p>
 */
public final class Conversations {
    /** How far away the "Talk to colonist" key reaches the colonist under the crosshair, in blocks. */
    private static final double LOOK_RANGE = 16.0;
    /** Looking at nobody, the key picks the nearest colonist within this range, in blocks. */
    private static final double NEAREST_RANGE = 16.0;
    /** How often partners are checked for having left the colony, in ticks. */
    private static final int CHECK_INTERVAL = 20;

    private static final class Entry {
        volatile ChatMode mode = ChatMode.PLAYERS;
        volatile Partner partner;
        /** Last membership sent to the client, so it is only resent when it changes. */
        volatile Boolean member;
    }

    private record Partner(ResourceKey<Level> dimension, int colonyId, int citizenId, String name, String jobKey) {
    }

    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    private final TalkService talk;

    public Conversations(TalkService talk) {
        this.talk = talk;
    }

    /** Whether the player's next chat message goes to colonists instead of the server chat. */
    public boolean wantsChat(ServerPlayer player) {
        Entry entry = players.get(player.getUUID());
        return entry != null && (entry.partner != null || entry.mode == ChatMode.COLONY);
    }

    /** A chat message for colonists (see {@link #wantsChat}). */
    public void onChat(ServerPlayer player, String text) {
        Entry entry = entry(player);
        Partner partner = entry.partner;
        if (partner != null) {
            ICitizenData citizen = TalkService.resolve(partner.dimension(), partner.colonyId(), partner.citizenId());
            if (citizen != null && TalkService.isInside(player, citizen.getColony())) {
                talk.sayPrivately(player, citizen, text);
            } else {
                release(player, true);
                player.sendSystemMessage(Component.translatable("livingcolonists.colony.outside"));
            }
            return;
        }
        IColony colony = TalkService.chatColony(player);
        if (colony == null) {
            player.sendSystemMessage(Component.translatable("livingcolonists.colony.outside"));
            return;
        }
        talk.sayToColony(player, colony, text);
    }

    public void onAction(ServerPlayer player, ConversationAction.Action action) {
        switch (action) {
            case TALK -> pressTalk(player, true);
            case FOCUS -> pressTalk(player, false);
            case CYCLE_MODE -> setMode(player, entry(player).mode.next());
            case RELEASE -> release(player, true);
            case MODE_PLAYERS -> setMode(player, ChatMode.PLAYERS);
            case MODE_COLONY -> setMode(player, ChatMode.COLONY);
            case CHAT_COLONY -> chatToColony(player);
        }
    }

    /**
     * The "Talk to colonist" key: pick the colonist under the crosshair; looking at nobody, end the
     * private talk, or pick the nearest colonist if there is no talk yet.
     */
    public void pressTalk(ServerPlayer player, boolean openChat) {
        Optional<AbstractEntityCitizen> lookedAt = TalkService.findListener(player, LOOK_RANGE, true);
        if (lookedAt.isPresent()) {
            lock(player, lookedAt.get().getCitizenData(), openChat);
            return;
        }
        if (entry(player).partner != null) {
            release(player, true);
            return;
        }
        Optional<AbstractEntityCitizen> nearest = TalkService.findListener(player, NEAREST_RANGE, false);
        if (nearest.isPresent()) {
            lock(player, nearest.get().getCitizenData(), openChat);
        } else {
            player.displayClientMessage(Component.translatable(openChat
                    ? "livingcolonists.partner.look_to_talk" : "livingcolonists.partner.look_to_focus"), true);
        }
    }

    /** Picks a colonist by colony and citizen id: clicking a name in chat. */
    public void lock(ServerPlayer player, int colonyId, int citizenId) {
        ICitizenData citizen = TalkService.resolve(player.level().dimension(), colonyId, citizenId);
        if (citizen == null) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.nobody"));
            return;
        }
        lock(player, citizen, false);
    }

    public void lock(ServerPlayer player, ICitizenData citizen, boolean openChat) {
        IColony colony = citizen.getColony();
        if (!TalkService.isInside(player, colony)) {
            player.sendSystemMessage(Component.translatable("livingcolonists.colony.outside"));
            return;
        }
        if (!TalkService.mayTalk(player, colony)) {
            return;
        }
        Entry entry = entry(player);
        Speaker speaker = TalkService.speakerOf(citizen);
        entry.partner = new Partner(colony.getDimension(), colony.getID(), citizen.getId(), speaker.name(),
                speaker.jobTranslationKey() == null ? "" : speaker.jobTranslationKey());
        player.displayClientMessage(Component.translatable("livingcolonists.partner.locked", speaker.name()), true);
        sync(player, entry, openChat);
    }

    public void release(ServerPlayer player, boolean notify) {
        Entry entry = players.get(player.getUUID());
        Partner partner = entry == null ? null : entry.partner;
        if (partner == null) {
            return;
        }
        entry.partner = null;
        if (notify) {
            player.displayClientMessage(Component.translatable("livingcolonists.partner.released", partner.name()), true);
        }
        sync(player, entry, false);
    }

    /** Choosing a mode also ends a private talk. */
    /** The "Write to the colony" key: works inside a colony's borders, and opens the chat there. */
    public void chatToColony(ServerPlayer player) {
        IColony colony = TalkService.chatColony(player);
        if (colony == null) {
            player.displayClientMessage(Component.translatable("livingcolonists.colony.outside"), true);
            return;
        }
        Entry entry = entry(player);
        entry.partner = null;
        entry.mode = ChatMode.COLONY;
        sync(player, entry, true);
    }

    public void setMode(ServerPlayer player, ChatMode mode) {
        Entry entry = entry(player);
        entry.partner = null;
        entry.mode = mode;
        player.displayClientMessage(Component.translatable("livingcolonists.mode." + mode.name().toLowerCase(Locale.ROOT)), true);
        sync(player, entry, false);
    }

    /**
     * Ends private talks whose player has left the colony or whose colonist is gone, and tells
     * clients when their colony membership changes.
     */
    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Entry entry = entry(player);
            boolean member = isMember(player);
            if (!Boolean.valueOf(member).equals(entry.member)) {
                entry.member = member;
                sync(player, entry, false);
            }
        }
        for (Map.Entry<UUID, Entry> e : players.entrySet()) {
            Partner partner = e.getValue().partner;
            ServerPlayer player = partner == null ? null : server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                continue;
            }
            ICitizenData citizen = TalkService.resolve(partner.dimension(), partner.colonyId(), partner.citizenId());
            if (citizen == null || !TalkService.isInside(player, citizen.getColony())) {
                release(player, true);
            }
        }
    }

    /** The colonist the player is talking to privately, if any and still in the colony. */
    public Optional<ICitizenData> partner(ServerPlayer player) {
        Entry entry = players.get(player.getUUID());
        Partner partner = entry == null ? null : entry.partner;
        return partner == null ? Optional.empty()
                : Optional.ofNullable(TalkService.resolve(partner.dimension(), partner.colonyId(), partner.citizenId()));
    }

    public void forget(UUID playerId) {
        players.remove(playerId);
    }

    public void clear() {
        players.clear();
    }

    private Entry entry(ServerPlayer player) {
        return players.computeIfAbsent(player.getUUID(), id -> new Entry());
    }

    /** Owner, officer or friend of any colony. */
    private static boolean isMember(ServerPlayer player) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (TalkService.role(player, colony) != ColonyAccess.Role.OTHER) {
                return true;
            }
        }
        return false;
    }

    private static void sync(ServerPlayer player, Entry entry, boolean openChat) {
        Partner partner = entry.partner;
        PacketDistributor.sendToPlayer(player, new ConversationState(entry.mode,
                partner == null ? "" : partner.name(), partner == null ? "" : partner.jobKey(), openChat,
                Boolean.TRUE.equals(entry.member)));
    }
}
