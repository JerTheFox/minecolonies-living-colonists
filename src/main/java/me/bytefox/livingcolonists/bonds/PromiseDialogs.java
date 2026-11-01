package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.memory.PlayerMarks;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Promises made in MineColonies' own windows: "I will work on it!" in a colonist's request or need dialog, and the
 * request's fate (delivered, cancelled) that keeps or drops the promise. Server thread only.
 */
final class PromiseDialogs {
    /** "I will work on it!" in a colonist's request dialog is a promise due in one Minecraft day. */
    static final long REQUEST_PROMISE_TICKS = BondsService.DAY;
    /** How often promised colony requests are checked, in server ticks (one second): finished ones vanish fast. */
    static final int REQUEST_CHECK_INTERVAL = 20;
    static final String BUTTON_OKAY = "com.minecolonies.coremod.gui.chat.okay";
    static final String BUTTON_FULFILL = "com.minecolonies.coremod.gui.chat.fulfill";
    static final String BUTTON_CANCEL = "com.minecolonies.coremod.gui.chat.cancel";
    /** {@link Promise#requestId} of a promise made in a plain dialog: this prefix and the dialog's translation key. */
    static final String DIALOG = "dialog:";

    private final BondsService bonds;
    /** The last state seen of each promised colony request, by promise id, to tell delivered from cancelled. */
    private final Map<Long, String> requestStates = new HashMap<>();

    PromiseDialogs(BondsService bonds) {
        this.bonds = bonds;
    }

    void clear() {
        requestStates.clear();
    }

    /** What a requested thing is worth, by code from the item and the amount; ordinary (2) if unknown. */
    private static int worth(IRequest<?> asked) {
        if (asked == null || asked.getDisplayStacks().isEmpty()) {
            return 2;
        }
        ItemStack stack = asked.getDisplayStacks().get(0);
        int count = asked.getRequest() instanceof IDeliverable deliverable ? deliverable.getCount() : stack.getCount();
        return BondsService.worthOf(stack, count);
    }

    /**
     * A button pressed in a colonist's request dialog: "I will work on it!" becomes a promise tied
     * to the request; "Cancel" on a promised request drops it, a breach once it is overdue.
     */
    void onRequestResponse(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player,
                                  String playerName) {
        if (bonds.data() == null || !(request.getIdentifier() instanceof UUID id)) {
            return;
        }
        long now = BondsService.now(citizen.getColony());
        String requestId = id.toString();
        Promise existing = bonds.promises(citizen).stream().filter(p -> p.isOpen() && requestId.equals(p.requestId()))
                .findFirst().orElse(null);
        switch (button) {
            case BUTTON_OKAY -> {
                if (existing != null) {
                    return;
                }
                List<Promise> list = bonds.data().promises.computeIfAbsent(BondsService.key(citizen), k -> new ArrayList<>());
                if (list.stream().filter(Promise::isOpen).count() >= BondsService.MAX_OPEN) {
                    return;
                }
                IRequest<?> asked = request(citizen.getColony(), request);
                String what = asked == null ? "what they asked for"
                        : Bonds.requestedThing(asked.getDisplayStacks().isEmpty() ? asked.getShortDisplayString().getString()
                        : ItemNames.in(asked.getShortDisplayString().getString(), asked.getDisplayStacks().get(0)));
                String text = PlayerMarks.mark(player.getUUID(), playerName, playerName) + " promised to bring "
                        + citizen.getName() + " " + what + ".";
                list.add(new Promise(bonds.data().nextId++, player.getUUID(), playerName, text, worth(asked), Promise.About.SUPPLY, now,
                        now + REQUEST_PROMISE_TICKS, Promise.Status.OPEN, 0, false, false, requestId));
                bonds.data().playerNames.put(player.getUUID(), playerName);
                bonds.data().setDirty();
                player.displayClientMessage(Component.translatable("livingcolonists.promise.made", citizen.getName()),
                        true);
                ModLog.action("{} wrote down a promise from the request dialog: {}", citizen.getName(),
                        PlayerMarks.resolve(text));
                bonds.handOverBook(citizen, player);
            }
            case BUTTON_FULFILL -> {
                if (existing != null) {
                    bonds.resolve(citizen, existing.id(), Promise.Status.KEPT, now);
                }
            }
            case BUTTON_CANCEL -> {
                if (existing != null) {
                    bonds.resolve(citizen, existing.id(), Bonds.cancelledByMaker(existing, now), now);
                }
            }
            default -> {
            }
        }
    }

    /**
     * "I will work on it!" in a plain dialog the colonist opens about a need becomes a promise:
     * a hospital or a cure, an upgrade of their workplace, a warehouse, a guard tower near home or
     * work, better food. Other dialogs (a full chest, the rain) are no promise. Building and health
     * promises close by themselves when the building is finished or the colonist is cured.
     */
    void onDialogResponse(ICitizenData citizen, String inquiry, String button, ServerPlayer player,
                                 String playerName) {
        if (bonds.data() == null || !BUTTON_OKAY.equals(button)) {
            return;
        }
        String name = citizen.getName();
        String text;
        Promise.About about;
        int days;
        switch (inquiry) {
            case "com.minecolonies.coremod.ai.nohospital", "com.minecolonies.coremod.ai.waitingforcure" -> {
                text = playerName + " promised to get " + name + " cured.";
                about = Promise.About.HEALTH;
                days = 2;
            }
            case "com.minecolonies.core.restaurant.poorrestaurant", "com.minecolonies.coremod.request.toolow" -> {
                if (citizen.getWorkBuilding() == null) {
                    return;
                }
                text = playerName + " promised to upgrade " + name + "'s "
                        + Component.translatable(citizen.getWorkBuilding().getBuildingDisplayName()).getString() + ".";
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.job.deliveryman.nowarehouse" -> {
                text = playerName + " promised to build or upgrade the "
                        + Component.translatable(ModBuildings.wareHouse.get().getTranslationKey()).getString() + ".";
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.gui.chat.noguardnearhome", "com.minecolonies.coremod.gui.chat.noguardnearwork" -> {
                text = playerName + " promised to build a "
                        + Component.translatable(ModBuildings.guardTower.get().getTranslationKey()).getString() + " near "
                        + name + (inquiry.endsWith("home") ? "'s home." : "'s workplace.");
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.entity.citizen.no.fooddiversity",
                 "com.minecolonies.coremod.entity.citizen.no.fooddiversity.urgent" -> {
                text = playerName + " promised " + name + " more varied food in the dining hall.";
                about = Promise.About.FOOD;
                days = 0;
            }
            case "com.minecolonies.coremod.entity.citizen.no.foodquality",
                 "com.minecolonies.coremod.entity.citizen.no.foodquality.urgent" -> {
                text = playerName + " promised " + name + " better food in the dining hall.";
                about = Promise.About.FOOD;
                days = 0;
            }
            default -> {
                return;
            }
        }
        String id = DIALOG + inquiry;
        List<Promise> list = bonds.data().promises.computeIfAbsent(BondsService.key(citizen), k -> new ArrayList<>());
        if (list.stream().anyMatch(p -> p.isOpen() && id.equals(p.requestId()))
                || list.stream().filter(Promise::isOpen).count() >= BondsService.MAX_OPEN) {
            return;
        }
        long now = BondsService.now(citizen.getColony());
        text = PlayerMarks.markNames(text, Map.of(player.getUUID(), List.of(playerName)));
        list.add(new Promise(bonds.data().nextId++, player.getUUID(), playerName, text, about == Promise.About.FOOD ? 2 : 3, about, now,
                days > 0 ? now + days * BondsService.DAY : 0,
                Promise.Status.OPEN, 0, BondsService.needUnmet(citizen, about), false, id));
        bonds.data().playerNames.put(player.getUUID(), playerName);
        bonds.data().setDirty();
        player.displayClientMessage(Component.translatable("livingcolonists.promise.made", name), true);
        ModLog.action("{} wrote down a promise from a dialog: {}", name, PlayerMarks.resolve(text));
        bonds.handOverBook(citizen, player);
    }

    /** Promises tied to colony requests close when the request is delivered or cancelled. */
    void watchRequests(MinecraftServer server) {
        for (Map.Entry<String, List<Promise>> entry : List.copyOf(bonds.data().promises.entrySet())) {
            String[] parts = entry.getKey().split(":");
            IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
            ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
            if (citizen == null) {
                continue;
            }
            for (Promise promise : List.copyOf(entry.getValue())) {
                if (!promise.isOpen() || promise.requestId().isEmpty() || promise.requestId().startsWith(DIALOG)) {
                    continue;
                }
                IRequest<?> request = request(colony, new StandardToken(UUID.fromString(promise.requestId())));
                String state = request == null ? null : request.getState().name();
                var outcome = Bonds.requestOutcome(state, requestStates.get(promise.id()));
                if (outcome.isEmpty()) {
                    requestStates.put(promise.id(), state);
                    continue;
                }
                requestStates.remove(promise.id());
                if (outcome.get() == Promise.Status.KEPT) {
                    bonds.watch().keptByItself(server, citizen, promise, "the request was delivered");
                } else {
                    bonds.resolve(citizen, promise.id(), outcome.get(), BondsService.now(colony));
                }
            }
        }
    }

    /** The colony request for a token, or null once the request manager forgot it. */
    private static IRequest<?> request(IColony colony, IToken<?> token) {
        try {
            return colony.getRequestManager().getRequestForToken(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
