package me.bytefox.livingcolonists.perception;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import me.bytefox.livingcolonists.network.ItemNamesReply;
import me.bytefox.livingcolonists.network.ItemNamesRequest;
import me.bytefox.livingcolonists.talk.Languages;
import me.bytefox.livingcolonists.talk.Translations;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Item names in the players' languages, for what colonists are told: a dedicated server knows only
 * the English names of vanilla items, so left alone the model makes up its own translations
 * ("крупноземлистая земля" for Coarse Dirt). The players' clients name the items instead: the
 * server asks one player of each language for the names it lacks and keeps them until it stops.
 * Until a name comes, the English one is used. Server thread only.
 */
public final class ItemNames {
    /** How often the names still wanted are asked for, in ticks (two seconds). */
    private static final int ASK_INTERVAL = 40;
    /** A name asked for and not answered is asked again after this long (twenty seconds). */
    private static final int ASK_AGAIN = 400;
    private static final int MAX_WANTED = 512;

    /** By locale ({@code ru_ru}), the names by translation key. */
    private static final Map<String, Map<String, String>> NAMES = new HashMap<>();
    private static final Map<String, Set<String>> WANTED = new HashMap<>();
    /** By locale, the keys asked for and when. */
    private static final Map<String, Map<String, Long>> ASKED = new HashMap<>();
    /** The languages of the players online, other than English. */
    private static Set<String> locales = Set.of();

    private ItemNames() {
    }

    /** The item's English name, with its names in the players' other languages once they are known. */
    public static String of(ItemStack stack) {
        String english = stack.getHoverName().getString();
        if (stack.isEmpty() || stack.has(DataComponents.CUSTOM_NAME)) {
            return english;
        }
        String key = stack.getDescriptionId();
        Map<String, String> known = new TreeMap<>();
        for (String locale : locales) {
            String name = NAMES.getOrDefault(locale, Map.of()).get(key);
            if (name != null) {
                known.put(Languages.ofLocale(locale), name);
            } else {
                Set<String> wanted = WANTED.computeIfAbsent(locale, l -> new LinkedHashSet<>());
                if (wanted.size() < MAX_WANTED) {
                    wanted.add(key);
                }
            }
        }
        return ItemNameText.withTranslations(english, known);
    }

    /**
     * A MineColonies name (a building, a work order) with its names in the players' other languages.
     * Those come from MineColonies' own translations in its jar, which the server has.
     */
    public static String named(Component name) {
        String english = name.getString();
        if (!(name.getContents() instanceof TranslatableContents translatable)) {
            return english;
        }
        Map<String, String> known = new TreeMap<>();
        for (String locale : locales) {
            String local = Translations.of("minecolonies", locale).get(translatable.getKey());
            if (local != null && !local.isBlank()) {
                known.put(Languages.ofLocale(locale), local);
            }
        }
        return ItemNameText.withTranslations(english, known);
    }

    /** {@code text} with the item's English name in it replaced by {@link #of}: "34 Coarse Dirt (in Russian: …)". */
    public static String in(String text, ItemStack stack) {
        String english = stack.getHoverName().getString();
        return english.isEmpty() || !text.contains(english) ? text : text.replace(english, of(stack));
    }

    /** Asks the players for the names still wanted, a few at a time. Server tick. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % ASK_INTERVAL != 0) {
            return;
        }
        Map<String, ServerPlayer> speakers = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String locale = locale(player);
            if (!locale.startsWith("en")) {
                speakers.putIfAbsent(locale, player);
            }
        }
        locales = Set.copyOf(speakers.keySet());
        long now = server.getTickCount();
        speakers.forEach((locale, player) -> {
            Set<String> wanted = WANTED.getOrDefault(locale, Set.of());
            Map<String, Long> asked = ASKED.computeIfAbsent(locale, l -> new HashMap<>());
            List<String> keys = wanted.stream().filter(key -> now - asked.getOrDefault(key, Long.MIN_VALUE / 2) >= ASK_AGAIN)
                    .limit(ItemNamesRequest.MAX_KEYS).toList();
            if (!keys.isEmpty()) {
                keys.forEach(key -> asked.put(key, now));
                PacketDistributor.sendToPlayer(player, new ItemNamesRequest(keys));
            }
        });
    }

    /** Names a client sent: only those asked of its language are taken. */
    public static void received(ServerPlayer player, ItemNamesReply reply) {
        String locale = locale(player);
        Map<String, Long> asked = ASKED.getOrDefault(locale, Map.of());
        Set<String> wanted = WANTED.getOrDefault(locale, new LinkedHashSet<>());
        reply.names().forEach((key, name) -> {
            if (asked.containsKey(key)) {
                String text = ItemNameText.clean(key, name);
                if (!text.isEmpty()) {
                    NAMES.computeIfAbsent(locale, l -> new HashMap<>()).put(key, text);
                }
                wanted.remove(key);
                asked.remove(key);
            }
        });
    }

    public static void clear() {
        NAMES.clear();
        WANTED.clear();
        ASKED.clear();
        locales = Set.of();
    }

    private static String locale(ServerPlayer player) {
        String locale = player.clientInformation().language();
        return locale == null || !locale.matches("[a-z]{2,3}(_[a-z0-9]{2,4})?") ? "en_us" : locale;
    }
}
