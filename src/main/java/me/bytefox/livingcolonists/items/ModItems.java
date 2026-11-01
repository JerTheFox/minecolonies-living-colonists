package me.bytefox.livingcolonists.items;

import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's items. */
public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LivingColonists.MODID);

    /** The player's promises, opened with a right click (#60). */
    public static final DeferredItem<Item> PROMISE_BOOK = ITEMS.registerSimpleItem("promise_book",
            new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(PROMISE_BOOK);
            }
        });
    }
}
