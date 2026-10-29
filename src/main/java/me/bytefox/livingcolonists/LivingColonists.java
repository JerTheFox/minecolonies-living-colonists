package me.bytefox.livingcolonists;

import com.mojang.logging.LogUtils;
import me.bytefox.livingcolonists.talk.TalkCommand;
import me.bytefox.livingcolonists.talk.TalkService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(LivingColonists.MODID)
public final class LivingColonists {
    public static final String MODID = "livingcolonists";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final TalkService talk = new TalkService();

    public LivingColonists(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> TalkCommand.register(event.getDispatcher(), talk));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Living Colonists loaded, model {} at {}, API key {}", Config.MODEL.get(), Config.BASE_URL.get(),
                Config.apiKey().isPresent() ? "found" : "missing: set " + Config.API_KEY_ENV.get());
    }
}
