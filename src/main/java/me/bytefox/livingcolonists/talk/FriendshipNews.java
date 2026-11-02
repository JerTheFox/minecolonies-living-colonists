package me.bytefox.livingcolonists.talk;

import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.friendship.FriendshipLevels;
import me.bytefox.livingcolonists.friendship.GiftTastes;
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A friendship milestone (#67) in words: the colonist tells the player privately, and both remember it. At "good friend"
 * the colonist lets slip their odd favourite gift, which the notebook then shows.
 */
final class FriendshipNews {
    private static final Map<String, String> AS = Map.of("friend", "a friend", "good", "a good friend",
            "close", "a close friend", "best", "their best friend");

    private final TalkService talk;
    private final BondsService bonds;
    private final MemoryService memory;

    FriendshipNews(TalkService talk, BondsService bonds, MemoryService memory) {
        this.talk = talk;
        this.bonds = bonds;
        this.memory = memory;
    }

    void milestone(GameEvents.FriendshipMilestone event) {
        ICitizenData citizen = event.citizen();
        MinecraftServer server = citizen.getColony().getWorld() == null ? null : citizen.getColony().getWorld().getServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(event.player());
        String said = FriendshipLevels.milestone(event.level()).orElse("");
        if (player == null || said.isEmpty()) {
            return;
        }
        if (said.contains("{favourite}")) {
            ItemStack favourite = GiftTastes.oddLove(citizen.getColony().getID() + ":" + citizen.getId());
            if (favourite.isEmpty()) {
                return;
            }
            bonds.learnTaste(citizen, event.player(), BuiltInRegistries.ITEM.getKey(favourite.getItem()).toString(), Taste.LOVE);
            said = said.replace("{favourite}", ItemNames.of(favourite));
        }
        String playerName = Audience.playerName(player);
        Counters.add("friendship.milestones");
        ModLog.action("{} now sees {} as {}", citizen.getName(), playerName, AS.getOrDefault(event.level(), event.level()));
        memory.rememberSeen(citizen.getColony(), List.of(citizen.getId()), playerName,
                citizen.getName() + " has come to see " + playerName + " as " + AS.getOrDefault(event.level(), event.level()) + ".", 4);
        talk.writeFirst(player, citizen, said, false, null, false, Airtime.Kind.NOTE);
    }
}
