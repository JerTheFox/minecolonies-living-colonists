package me.bytefox.livingcolonists.dev;

import java.util.Optional;

import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.network.ConversationAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Live runs (#64), client side: plays the player's part on a real server and logs {@code Live run <name>: PASS} or
 * {@code FAIL}, with screenshots in {@code run/screenshots/live-*.png}.
 * <p>
 * {@code promise}: the player promises a colonist something in a private talk (the real model replies), the talk is
 * digested at once ({@code /lcdebug memory digest}, the player must be an operator), and the player clicks
 * "[Yes, I promise]" in the chat; passes when the server says the promise is confirmed.
 */
@EventBusSubscriber(modid = "livingcolonists", value = Dist.CLIENT)
public final class LiveRunsClient {
    private static final String PROMISE = "I promise I'll bring you three loaves of bread tomorrow.";
    private static final int TIMEOUT_TICKS = 20 * 180;

    private enum Step { LOOK, SAID, DIGESTED, ASKED, CLICKED, LISTED, DONE }

    private static Step step = Step.LOOK;
    private static int ticks;
    private static int stepTicks;
    private static Style yes;
    private static boolean confirmed;
    private static String colonist;

    private LiveRunsClient() {
    }

    @SubscribeEvent
    public static void chat(ClientChatReceivedEvent event) {
        if (!"promise".equals(LiveRuns.run())) {
            return;
        }
        Component message = event.getMessage();
        if (message.getString().startsWith(LiveRuns.READY)) {
            colonist = message.getString().substring(LiveRuns.READY.length());
        }
        if (message.getContents() instanceof TranslatableContents text && text.getKey().equals("livingcolonists.promise.confirmed")) {
            confirmed = true;
        }
        message.visit((style, part) -> {
            if (style.getClickEvent() != null && style.getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND
                    && style.getClickEvent().getValue().startsWith("/lc promise yes ")) {
                yes = style;
            }
            return Optional.empty();
        }, Style.EMPTY);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!"promise".equals(LiveRuns.run()) || step == Step.DONE || minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        ticks++;
        stepTicks++;
        if (ticks > TIMEOUT_TICKS) {
            finish("FAIL, stuck at " + step);
            return;
        }
        switch (step) {
            case LOOK -> {
                // Only the colonist the server put in front of the player, once it has stopped there.
                if (colonist != null && minecraft.hitResult instanceof EntityHitResult hit
                        && hit.getEntity() instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen
                        && citizen.getCitizenDataView() != null && colonist.equals(citizen.getCitizenDataView().getName())) {
                    PacketDistributor.sendToServer(new ConversationAction(ConversationAction.Action.TALK));
                    next(Step.SAID);
                }
            }
            case SAID -> {
                if (stepTicks == 20) {
                    minecraft.getConnection().sendChat(PROMISE);
                    ModLog.info("Live run promise: said '{}'", PROMISE);
                } else if (stepTicks == 20 * 25) {
                    minecraft.getConnection().sendCommand("lcdebug memory digest");
                    next(Step.DIGESTED);
                }
            }
            case DIGESTED -> {
                if (yes != null) {
                    minecraft.setScreen(new ChatScreen(""));
                    next(Step.ASKED);
                }
            }
            case ASKED -> {
                if (stepTicks == 20) {
                    shot(minecraft, "live-promise-1-asked");
                } else if (stepTicks == 40) {
                    // The same handler a mouse click on the chat line goes through.
                    minecraft.screen.handleComponentClicked(yes);
                    next(Step.CLICKED);
                }
            }
            case CLICKED -> {
                if (confirmed) {
                    minecraft.getConnection().sendCommand("lc promises");
                    next(Step.LISTED);
                }
            }
            case LISTED -> {
                if (stepTicks == 40) {
                    shot(minecraft, "live-promise-2-confirmed");
                    finish("PASS");
                }
            }
            default -> {
            }
        }
    }

    private static void next(Step to) {
        ModLog.info("Live run promise: {} -> {}", step, to);
        step = to;
        stepTicks = 0;
    }

    private static void finish(String result) {
        step = Step.DONE;
        ModLog.info("Live run promise: {}", result);
    }

    private static void shot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(),
                message -> ModLog.info("Live run: {}", message.getString()));
    }
}
