/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/FetchQueue.java, ErrandManager.java (fetch and deliver) and
 * tools/FetchItemAction.java, DeliverItemAction.java, CourierBoardAction.java. Changed: one item per
 * job, found by code; the player confirms; undo takes a job off the board before it is picked up.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Couriers carrying things on the player's word: from the warehouse to the player, or to a
 * building. Jobs wait on a board until a courier is free; each courier takes one at a time.
 */
public final class Couriers {
    private static final int MAX_BOARD = 10;
    private static final int MAX_COUNT = 256;
    private static final long WALK_TICKS = 4 * Walks.MINUTE;

    private enum Stage { WAITING, TO_WAREHOUSE, TO_TARGET }

    private static final class Job {
        final int colonyId;
        final UUID player;
        final String playerName;
        final Item item;
        final int count;
        /** Null when the player gets it. */
        final IBuilding target;
        Stage stage = Stage.WAITING;
        ICitizenData courier;
        int carried;
        /** A courier who couldn't reach the warehouse gets the job back on the board once. */
        boolean retried;

        Job(int colonyId, UUID player, String playerName, Item item, int count, IBuilding target) {
            this.colonyId = colonyId;
            this.player = player;
            this.playerName = playerName;
            this.item = item;
            this.count = count;
            this.target = target;
        }

        String describe() {
            return count + " " + ItemNames.of(new ItemStack(item)) + " for "
                    + (target == null ? playerName : "the " + ItemNames.named(Component.translatable(target.getBuildingType().getTranslationKey())))
                    + (stage == Stage.WAITING ? " (waiting for a courier)" : " (" + courier.getName() + " is on it)");
        }
    }

    private final Walks walks;
    private final ItemIndex items;
    private final List<Job> board = new ArrayList<>();

    public Couriers(Walks walks, ItemIndex items) {
        this.walks = walks;
        this.items = items;
    }

    public List<Errand> errands() {
        return List.of(new Fetch(false), new Fetch(true), new Board());
    }

    /** Every five seconds: free couriers take the oldest jobs; a job whose courier gave up is sorted out. */
    public void tick(MinecraftServer server) {
        if (board.isEmpty() || server.getTickCount() % 100 != 0) {
            return;
        }
        for (Job job : List.copyOf(board)) {
            if (job.stage != Stage.WAITING && !walks.isWalking(job.courier)) {
                giveUp(server, job);
                continue;
            }
            if (job.stage != Stage.WAITING) {
                continue;
            }
            IColony colony = colony(job.colonyId);
            if (colony == null) {
                board.remove(job);
                continue;
            }
            IBuilding warehouse = warehouse(colony);
            ICitizenData courier = freeCourier(colony, warehouse);
            if (warehouse == null || courier == null) {
                continue;
            }
            job.courier = courier;
            job.stage = Stage.TO_WAREHOUSE;
            if (!walks.start(server, courier, job.player, Walks.Kind.TO_POS, warehouse.getPosition(), WALK_TICKS, 0,
                    "to the warehouse for " + job.describe(), () -> pickUp(server, colony, warehouse, job))) {
                job.stage = Stage.WAITING;
                job.courier = null;
            }
        }
    }

    private void pickUp(MinecraftServer server, IColony colony, IBuilding warehouse, Job job) {
        IItemHandler pockets = job.courier.getInventory();
        int left = job.count;
        for (BlockPos pos : warehouse.getContainers()) {
            if (left <= 0 || !(colony.getWorld().getBlockEntity(pos) instanceof AbstractTileEntityRack rack)) {
                continue;
            }
            var inventory = rack.getInventory();
            for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++) {
                if (!inventory.getStackInSlot(slot).is(job.item)) {
                    continue;
                }
                ItemStack taken = inventory.extractItem(slot, Math.min(left, inventory.getStackInSlot(slot).getCount()), false);
                ItemStack rest = ItemHandlerHelper.insertItemStacked(pockets, taken, false);
                job.carried += taken.getCount() - rest.getCount();
                left -= taken.getCount() - rest.getCount();
                if (!rest.isEmpty()) {
                    inventory.insertItem(slot, rest, false);
                    left = 0;
                }
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(job.player);
        if (job.carried == 0) {
            board.remove(job);
            tell(player, Component.translatable("livingcolonists.courier.none", job.courier.getName(),
                    new ItemStack(job.item).getHoverName()));
            return;
        }
        job.stage = Stage.TO_TARGET;
        Runnable handOver = () -> handOver(server, colony, job);
        boolean going = job.target == null
                ? walks.start(server, job.courier, job.player, Walks.Kind.TO_PLAYER, null, WALK_TICKS, Walks.MINUTE / 2,
                "bringing " + job.describe(), handOver)
                : walks.start(server, job.courier, job.player, Walks.Kind.TO_POS, job.target.getPosition(), WALK_TICKS, 0,
                "bringing " + job.describe(), handOver);
        if (!going) {
            board.remove(job);
        }
    }

    /**
     * The courier's walk ran out or was stopped: before the pick-up the job goes back on the board
     * once; with the things already carried, the courier keeps them and the player is told.
     */
    private void giveUp(MinecraftServer server, Job job) {
        ServerPlayer player = server.getPlayerList().getPlayer(job.player);
        if (job.stage == Stage.TO_WAREHOUSE && !job.retried) {
            job.retried = true;
            job.stage = Stage.WAITING;
            job.courier = null;
            return;
        }
        board.remove(job);
        tell(player, Component.translatable("livingcolonists.courier.gave_up", job.courier.getName(),
                new ItemStack(job.item).getHoverName()));
    }

    private void handOver(MinecraftServer server, IColony colony, Job job) {
        board.remove(job);
        IItemHandler pockets = job.courier.getInventory();
        ServerPlayer player = server.getPlayerList().getPlayer(job.player);
        var entity = job.courier.getEntity();
        int given = 0;
        for (int slot = 0; slot < pockets.getSlots() && given < job.carried; slot++) {
            if (!pockets.getStackInSlot(slot).is(job.item)) {
                continue;
            }
            ItemStack stack = pockets.extractItem(slot, job.carried - given, false);
            given += stack.getCount();
            if (job.target == null) {
                if (player == null) {
                    pockets.insertItem(slot, stack, false);
                    break;
                }
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            } else if (job.target != null) {
                IItemHandler into = job.target.getItemHandlerCap();
                ItemStack rest = into == null ? stack : ItemHandlerHelper.insertItemStacked(into, stack, false);
                if (!rest.isEmpty() && entity.isPresent()) {
                    colony.getWorld().addFreshEntity(new ItemEntity(colony.getWorld(), entity.get().getX(),
                            entity.get().getY(), entity.get().getZ(), rest));
                }
            }
        }
        ModLog.action("{} delivered {} {}", job.courier.getName(), given, job.item);
        tell(player, Component.translatable(job.target == null ? "livingcolonists.courier.brought" : "livingcolonists.courier.stocked",
                job.courier.getName(), given, new ItemStack(job.item).getHoverName(),
                job.target == null ? "" : Component.translatable(job.target.getBuildingType().getTranslationKey())));
    }

    private static void tell(ServerPlayer player, Component text) {
        if (player != null) {
            player.sendSystemMessage(text.copy().withStyle(ChatFormatting.GRAY));
        }
    }

    private ICitizenData freeCourier(IColony colony, IBuilding warehouse) {
        BlockPos at = warehouse == null ? BlockPos.ZERO : warehouse.getPosition();
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getJob() != null && c.getJob().getJobRegistryEntry().getKey().getPath().equals("deliveryman"))
                .filter(c -> c.getEntity().isPresent() && !walks.isWalking(c))
                .filter(c -> board.stream().noneMatch(job -> c.equals(job.courier)))
                .min(Comparator.comparingDouble(c -> c.getEntity().get().blockPosition().distSqr(at))).orElse(null);
    }

    private static IBuilding warehouse(IColony colony) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(b -> b.getBuildingType().getRegistryName().getPath().equals("warehouse") && b.getBuildingLevel() > 0)
                .findFirst().orElse(null);
    }

    private static IColony colony(int id) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getID() == id) {
                return colony;
            }
        }
        return null;
    }

    public void clear() {
        board.clear();
    }

    /** "Bring me 20 planks" or "take 20 planks to the bakery". */
    private final class Fetch implements Errand {
        private final boolean toBuilding;

        Fetch(boolean toBuilding) {
            this.toBuilding = toBuilding;
        }

        @Override
        public String name() {
            return toBuilding ? "deliver_item" : "fetch_item";
        }

        @Override
        public ToolSpec spec() {
            return toBuilding
                    ? Errands.orderSpec(name(), "Have a courier take something from the warehouse to a building "
                    + "(\"take 20 planks to the bakery\").", Map.of(
                    "item", "The item in plain English words", "count", "How many, as a number",
                    "building", "The building in plain English words"))
                    : Errands.orderSpec(name(), "Have a courier bring the player something from the warehouse "
                    + "(\"bring me 20 planks\").", Map.of(
                    "item", "The item in plain English words", "count", "How many, as a number"));
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            IColony colony = context.colony();
            ServerPlayer player = context.player();
            if (warehouse(colony) == null) {
                return new Result.Answer(Errand.error("The colony has no warehouse to take things from."));
            }
            if (colony.getCitizenManager().getCitizens().stream().noneMatch(c -> c.getJob() != null
                    && c.getJob().getJobRegistryEntry().getKey().getPath().equals("deliveryman"))) {
                return new Result.Answer(Errand.error("The colony has no courier to carry it."));
            }
            if (board.stream().filter(job -> job.colonyId == colony.getID()).count() >= MAX_BOARD) {
                return new Result.Answer(Errand.error("The couriers' board is full: wait until they carry some of it."));
            }
            String locale = player.clientInformation().language();
            String words = Errand.string(arguments, "item");
            if (!(items.find(words, locale) instanceof ItemMatch.Result.Found<Item> found)) {
                return new Result.Answer(Errand.notFound("single item", words));
            }
            IBuilding target = null;
            if (toBuilding) {
                String where = Errand.string(arguments, "building");
                if (!(ItemMatch.find(where, UpgradeBuilding.candidates(colony, locale)) instanceof ItemMatch.Result.Found<IBuilding> building)) {
                    return new Result.Answer(Errand.notFound("single building in the colony", where));
                }
                target = building.value();
            }
            int count = Math.max(1, Math.min(MAX_COUNT, number(Errand.string(arguments, "count"))));
            Item item = found.value();
            IBuilding to = target;
            Component itemName = new ItemStack(item).getHoverName();
            Component question = to == null
                    ? Component.translatable("livingcolonists.order.fetch.ask", count, itemName)
                    : Component.translatable("livingcolonists.order.deliver.ask", count, itemName,
                    Component.translatable(to.getBuildingType().getTranslationKey()));
            String playerName = TalkService.playerName(player);
            return new Result.Proposal(new Order(name(), (to == null ? "bring " : "take ") + count + " "
                    + itemName.getString() + (to == null ? " to the player" : " to a building"), question, confirming -> {
                Job job = new Job(colony.getID(), confirming.getUUID(), playerName, item, count, to);
                board.add(job);
                return new Outcome(true, Component.translatable("livingcolonists.order.fetch.done"), () -> {
                    if (job.stage == Stage.WAITING) {
                        board.remove(job);
                    }
                });
            }));
        }
    }

    /** What the couriers have to carry. */
    private final class Board implements Errand {
        @Override
        public String name() {
            return "courier_board";
        }

        @Override
        public ToolSpec spec() {
            return Errands.spec(name(), "What the couriers have been asked to carry and who is on it.", null, null);
        }

        @Override
        public boolean isOrder() {
            return false;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            JsonArray jobs = new JsonArray();
            board.stream().filter(job -> job.colonyId == context.colony().getID()).forEach(job -> jobs.add(job.describe()));
            JsonObject reply = new JsonObject();
            reply.addProperty("success", true);
            reply.add("jobs", jobs);
            if (jobs.isEmpty()) {
                reply.addProperty("info", "Nothing is waiting on the couriers' board.");
            }
            return new Result.Answer(reply);
        }
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
