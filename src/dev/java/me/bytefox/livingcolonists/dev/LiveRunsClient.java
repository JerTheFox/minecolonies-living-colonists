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
 * "[Yes, I promise]" in the chat; the server says the promise is confirmed, the notebook the colonist handed over
 * opens with it, and a mouse click on "[Give up]" in the notebook gives it up and refreshes the open pages.
 */
@EventBusSubscriber(modid = "livingcolonists", value = Dist.CLIENT)
public final class LiveRunsClient {
    private static final String PROMISE = "I promise I'll bring you three loaves of bread tomorrow.";
    private static final int TIMEOUT_TICKS = 20 * 180;

    private enum Step { LOOK, SAID, DIGESTED, ASKED, CLICKED, LISTED, BOOK, GAVE_UP, DONE }

    private static Step step = Step.LOOK;
    private static int ticks;
    private static int stepTicks;
    private static Style yes;
    private static boolean confirmed;
    private static boolean gaveUp;
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
        if (message.getContents() instanceof TranslatableContents text) {
            confirmed |= text.getKey().equals("livingcolonists.promise.confirmed");
            gaveUp |= text.getKey().equals("livingcolonists.promise.given_up");
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
                    minecraft.setScreen(null);
                    next(Step.BOOK);
                }
            }
            case BOOK -> {
                // The colonist handed over the notebook with the first confirmed promise: open it as a player would.
                if (stepTicks == 20) {
                    int slot = minecraft.player.getInventory().findSlotMatchingItem(
                            new net.minecraft.world.item.ItemStack(me.bytefox.livingcolonists.items.ModItems.PROMISE_BOOK.get()));
                    if (slot < 0) {
                        finish("FAIL, no notebook in the inventory");
                    } else if (slot < 9) {
                        minecraft.player.getInventory().selected = slot;
                    } else {
                        minecraft.gameMode.handlePickItem(slot);
                    }
                } else if (stepTicks == 40) {
                    minecraft.gameMode.useItem(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                } else if (stepTicks == 80) {
                    if (minecraft.screen instanceof me.bytefox.livingcolonists.client.PromiseBookScreen book) {
                        shot(minecraft, "live-promise-3-book");
                        // Find "[Give up]" on the pages and click it with the mouse, as a player would.
                        for (int y = 0; y < book.height; y += 2) {
                            for (int x = 0; x < book.width; x += 2) {
                                Style style = book.styleAt(x, y);
                                if (style != null && style.getClickEvent() != null
                                        && style.getClickEvent().getValue().startsWith("/lc promise no ")) {
                                    book.mouseClicked(x + 1, y + 1, 0);
                                    next(Step.GAVE_UP);
                                    return;
                                }
                            }
                        }
                        finish("FAIL, no [Give up] button in the notebook");
                    } else {
                        finish("FAIL, the notebook didn't open: " + minecraft.screen);
                    }
                }
            }
            case GAVE_UP -> {
                if (gaveUp && stepTicks > 40) {
                    if (minecraft.screen instanceof me.bytefox.livingcolonists.client.PromiseBookScreen) {
                        shot(minecraft, "live-promise-4-given-up");
                        finish("PASS");
                    } else {
                        finish("FAIL, the notebook closed after the click: " + minecraft.screen);
                    }
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
