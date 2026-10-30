/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/NotifyWhenAction.java and WatchManager. Changed: the item is found by
 * code, gains are summed between checks (workers empty their pockets at the hut), only a chat
 * message, no walking to the player.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.InventoryUtils;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A colonist keeps an eye out for an item while working and tells the player once they get it. */
public final class NotifyWhen implements Errand {
    /** A watch lasts an hour of play. */
    private static final long WATCH_TICKS = 60 * 60 * 20;
    private static final int MAX_WATCHES_PER_PLAYER = 5;
    private static final int MAX_COUNT = 1000;

    private static final class Watch {
        final ResourceKey<Level> dimension;
        final int colonyId;
        final int citizenId;
        final Item item;
        final int wanted;
        final UUID player;
        final long until;
        int last;
        int found;

        Watch(ResourceKey<Level> dimension, int colonyId, int citizenId, Item item, int wanted, UUID player, long until, int last) {
            this.dimension = dimension;
            this.colonyId = colonyId;
            this.citizenId = citizenId;
            this.item = item;
            this.wanted = wanted;
            this.player = player;
            this.until = until;
            this.last = last;
        }
    }

    private final ItemIndex items;
    private final List<Watch> watches = new ArrayList<>();

    public NotifyWhen(ItemIndex items) {
        this.items = items;
    }

    @Override
    public String name() {
        return "notify_when";
    }

    @Override
    public ToolSpec spec() {
        return Errands.spec(name(), "Keep an eye out for an item while you work and tell the player once you get it "
                + "(to a miner: \"let me know when you find diamonds\"). Lasts about an hour.", Map.of(
                "item", "The item in plain English words, like diamond or iron ore",
                "count", "How many to wait for, as a number; 1 if the player didn't say"));
    }

    @Override
    public boolean isOrder() {
        return false;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        ServerPlayer player = context.player();
        if (player == null) {
            return new Result.Answer(Errand.error("Only a player can ask to be told."));
        }
        String words = Errand.string(arguments, "item");
        if (!(items.find(words, player.clientInformation().language()) instanceof ItemMatch.Result.Found<Item> found)) {
            return new Result.Answer(Errand.notFound("single item", words));
        }
        if (watches.stream().filter(watch -> watch.player.equals(player.getUUID())).count() >= MAX_WATCHES_PER_PLAYER) {
            return new Result.Answer(Errand.error("You are already watching for too many things for this player."));
        }
        int wanted = Math.max(1, Math.min(MAX_COUNT, number(Errand.string(arguments, "count"))));
        ICitizenData citizen = context.citizen();
        watches.add(new Watch(context.colony().getDimension(), context.colony().getID(), citizen.getId(), found.value(),
                wanted, player.getUUID(), player.server.getTickCount() + WATCH_TICKS, count(citizen, found.value())));
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("info", "You keep an eye out for " + found.value().getDescription().getString() + " while "
                + "working; the player is told as soon as you get " + wanted + ". It lasts about an hour.");
        return new Result.Answer(reply);
    }

    /** Every second: adds up what each watched colonist gained and tells the player when it's enough. */
    void tick(MinecraftServer server) {
        long now = server.getTickCount();
        watches.removeIf(watch -> {
            if (now > watch.until) {
                return true;
            }
            IColony colony = IColonyManager.getInstance().getColonyByDimension(watch.colonyId, watch.dimension);
            ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(watch.citizenId);
            if (citizen == null) {
                return true;
            }
            int count = count(citizen, watch.item);
            if (count > watch.last) {
                watch.found += count - watch.last;
            }
            watch.last = count;
            if (watch.found < watch.wanted) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(watch.player);
            if (player != null) {
                player.sendSystemMessage(Component.translatable("livingcolonists.notify.found", citizen.getName(),
                        watch.found, new ItemStack(watch.item).getHoverName()).withStyle(ChatFormatting.GRAY));
            }
            return true;
        });
    }

    void clear() {
        watches.clear();
    }

    private static int count(ICitizenData citizen, Item item) {
        return citizen.getInventory() == null ? 0
                : InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(item));
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
