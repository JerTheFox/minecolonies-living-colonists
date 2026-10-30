package me.bytefox.livingcolonists.client;

import java.util.HashMap;
import java.util.Map;

import me.bytefox.livingcolonists.network.ItemNamesReply;
import me.bytefox.livingcolonists.network.ItemNamesRequest;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.network.PacketDistributor;

/** Names the items the server asks about in the game's current language; empty for those it can't. */
public final class ItemNamesClient {
    private ItemNamesClient() {
    }

    public static void apply(ItemNamesRequest request) {
        Map<String, String> names = new HashMap<>();
        for (String key : request.keys()) {
            names.put(key, I18n.exists(key) ? I18n.get(key) : "");
        }
        PacketDistributor.sendToServer(new ItemNamesReply(names));
    }
}
