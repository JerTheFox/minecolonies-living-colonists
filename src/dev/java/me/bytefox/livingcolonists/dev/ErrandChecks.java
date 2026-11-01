package me.bytefox.livingcolonists.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.errands.Errand;
import me.bytefox.livingcolonists.errands.Errands;
import me.bytefox.livingcolonists.errands.Notes;
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.perception.PerceptionService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Live run {@code errands} (#64): every look-up and order colonists can take, run on the real colony of the test world
 * through the mod's own {@link Errands} (as the model would call them, then the player's yes), each checked by what
 * changed in MineColonies. Orders are taken back with undo where they can be. What the world has no building for is
 * skipped and said so. {@code errands-restart} then checks that a tower set by an order got its settings back after a
 * server restart.
 */
final class ErrandChecks {
    private static final String STATE = "live-run-errands.properties";
    private static final int WAIT = 20 * 15;
    private static final int WALK = 20 * 240;

    private interface Check {
        /** Starts the check: null to go on with {@link #done}, or the result at once. */
        String start();

        /** Null while still waiting; otherwise the result. */
        default String done() {
            return "PASS";
        }

        default int limit() {
            return WAIT;
        }
    }

    private record Named(String name, Check check) {
    }

    private final MinecraftServer server;
    private final ServerPlayer player;
    private final IColony colony;
    private final Errands errands;
    private final List<Named> checks = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private final List<String> written = new ArrayList<>();
    private int index = -1;
    private int ticks;
    private boolean waiting;
    private boolean finished;

    ErrandChecks(MinecraftServer server, ServerPlayer player, IColony colony) {
        this.server = server;
        this.player = player;
        this.colony = colony;
        Notes.Writer writer = (to, citizen, purpose, inColonyOnly, failed, ownTopic) -> {
            written.add(citizen.getName() + ": " + purpose);
            return Notes.Delivery.SENT;
        };
        this.errands = new Errands(new PerceptionService(), writer, (citizen, deed) -> {
        });
        plan();
    }

    boolean finished() {
        return finished;
    }

    /** Before the server stops: towers go back to their own settings, as the mod does. */
    void beforeStop() {
        errands.beforeStop();
    }

    void tick() {
        if (finished) {
            return;
        }
        errands.tick(server);
        if (!waiting) {
            if (++index >= checks.size()) {
                finish();
                return;
            }
            ticks = 0;
            String result = attempt(() -> checks.get(index).check().start());
            if (result != null) {
                record(result);
            } else {
                waiting = true;
            }
            return;
        }
        ticks++;
        if (ticks % 10 != 0) {
            return;
        }
        String result = attempt(() -> checks.get(index).check().done());
        if (result != null) {
            record(result);
            waiting = false;
        } else if (ticks > checks.get(index).check().limit()) {
            record("FAIL, timed out");
            waiting = false;
        }
    }

    private interface Step {
        String run();
    }

    private static String attempt(Step step) {
        try {
            return step.run();
        } catch (RuntimeException | LinkageError e) {
            ModLog.warn("Live run errands: a check threw", e);
            return "FAIL, " + e;
        }
    }

    private void record(String result) {
        String line = checks.get(index).name() + ": " + result;
        results.add(line);
        ModLog.info("Live run errands: {}", line);
    }

    private void finish() {
        finished = true;
        long failed = results.stream().filter(r -> r.contains(": FAIL")).count();
        long skipped = results.stream().filter(r -> r.contains(": SKIP")).count();
        String summary = (results.size() - failed - skipped) + " passed, " + failed + " failed, " + skipped + " skipped";
        ModLog.info("Live run errands: {} {}", failed == 0 ? "PASS," : "FAIL,", summary);
    }

    // ---- the checks

    private void plan() {
        lookup("guard_gear", "knight", new JsonObject());
        lookup("guard_leaderboard", "knight", new JsonObject());
        lookup("courier_board", "deliveryman", new JsonObject());
        lookup("research_status", null, new JsonObject());
        lookup("check_stock", "deliveryman", args("item", "torch"));
        takeJob();
        moveHome();
        checks.add(new Named("request_craft", () -> {
            String item = taught();
            if (item == null) {
                return "SKIP, no crafter in the colony was taught a recipe";
            }
            return order("request_craft", colonist(null), args("item", item, "count", "2"), "make me two " + item, true, null, null)
                    + " (" + item + ")";
        }));
        ordered("sort_warehouse", null, new JsonObject(), "sort the warehouse", false, null);
        addToMenu();
        ordered("arm_guards", "knight", new JsonObject(), "arm the guards", true, null);
        farmerPlant();
        startResearch();
        fetch();
        deliver();
        guards();
        notifyWhen();
        leaveATowerForTheRestart();
    }

    private static JsonObject args(String... pairs) {
        JsonObject json = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            json.addProperty(pairs[i], pairs[i + 1]);
        }
        return json;
    }

    private ICitizenData colonist(String job) {
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> !c.isChild() && c.getEntity().isPresent())
                .filter(c -> job == null || c.getJob() != null && c.getJob().getJobRegistryEntry().getKey().getPath().equals(job))
                .findFirst().orElse(null);
    }

    private ICitizenData jobless() {
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> !c.isChild() && c.getEntity().isPresent() && c.getJob() == null).findFirst().orElse(null);
    }

    private IBuilding building(String path) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(b -> b.getBuildingType().getRegistryName().getPath().equals(path) && b.getBuildingLevel() > 0)
                .findFirst().orElse(null);
    }

    private JsonObject call(String tool, ICitizenData citizen, JsonObject arguments, String words) {
        if (words != null) {
            arguments.addProperty(Errands.PLAYER_WORDS, words);
        }
        String reply = errands.run(new ToolCall("live", tool, arguments.toString()),
                new Errand.Context(citizen, colony, player, words == null ? List.of() : List.of(words)));
        return JsonParser.parseString(reply).getAsJsonObject();
    }

    private static boolean success(JsonObject reply) {
        return reply.has("success") && reply.get("success").getAsBoolean();
    }

    private void lookup(String tool, String job, JsonObject arguments) {
        checks.add(new Named(tool, () -> {
            ICitizenData citizen = colonist(job);
            if (citizen == null) {
                return "SKIP, nobody works as " + job;
            }
            JsonObject reply = call(tool, citizen, arguments, null);
            return success(reply) ? "PASS" : "FAIL, " + reply;
        }));
    }

    /** Asks for an order, says yes and, if {@code undo}, takes it back; {@code changed} checks the world after the yes. */
    private void ordered(String tool, String job, JsonObject arguments, String words, boolean undo, BooleanSupplier changed) {
        checks.add(new Named(tool, () -> {
            ICitizenData citizen = colonist(job);
            if (citizen == null) {
                return "SKIP, nobody works as " + job;
            }
            return order(tool, citizen, arguments, words, undo, changed, null);
        }));
    }

    private String order(String tool, ICitizenData citizen, JsonObject arguments, String words, boolean undo,
                         BooleanSupplier changed, BooleanSupplier undone) {
        JsonObject reply = call(tool, citizen, arguments, words);
        if (!reply.has("waiting_for_yes")) {
            return "FAIL, not proposed: " + reply;
        }
        if (errands.confirm(player) != 1) {
            return "FAIL, the yes didn't carry it out";
        }
        if (changed != null && !changed.getAsBoolean()) {
            return "FAIL, nothing changed in the colony";
        }
        if (undo) {
            if (errands.undo(player) != 1) {
                return "FAIL, undo didn't work";
            }
            if (undone != null && !undone.getAsBoolean()) {
                return "FAIL, undo left it changed";
            }
            return "PASS, done and undone";
        }
        return "PASS";
    }

    /** What a crafter in the colony was taught to make, by its English name. */
    private String taught() {
        var recipes = com.minecolonies.api.colony.IColonyManager.getInstance().getRecipeManager();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            for (var module : building.getModules(com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule.class)) {
                for (var token : module.getRecipes()) {
                    var recipe = recipes.getRecipe(token);
                    if (recipe != null && !recipe.getPrimaryOutput().isEmpty()) {
                        return recipe.getPrimaryOutput().getHoverName().getString();
                    }
                }
            }
        }
        return null;
    }

    private void takeJob() {
        checks.add(new Named("take_job", () -> {
            ICitizenData citizen = jobless();
            if (citizen == null) {
                return "SKIP, nobody without a job";
            }
            WorkerBuildingModule free = null;
            for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                if (building.getBuildingLevel() == 0) {
                    continue;
                }
                for (WorkerBuildingModule module : building.getModules(WorkerBuildingModule.class)) {
                    if (module.getJobEntry() != null && !module.isFull() && free == null) {
                        free = module;
                    }
                }
            }
            ICitizenData moved = null;
            if (free == null) {
                // Every place is taken: free one for the check (not the guards', couriers' or cooks', used later).
                for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                    String path = building.getBuildingType().getRegistryName().getPath();
                    if (building.getBuildingLevel() == 0 || building instanceof AbstractBuildingGuards
                            || path.equals("deliveryman") || path.equals("cook")) {
                        continue;
                    }
                    for (WorkerBuildingModule module : building.getModules(WorkerBuildingModule.class)) {
                        if (free == null && module.getJobEntry() != null && !module.getAssignedCitizen().isEmpty()) {
                            moved = module.getAssignedCitizen().get(0);
                            module.removeCitizen(moved);
                            free = module;
                        }
                    }
                }
            }
            if (free == null) {
                return "SKIP, no workplace to free";
            }
            WorkerBuildingModule place = free;
            ICitizenData worker = moved;
            String job = free.getJobEntry().getKey().getPath();
            String words = Component.translatable(free.getJobEntry().getTranslationKey()).getString();
            String result = order("take_job", citizen, args("workplace", words), "take a job as " + words, true,
                    () -> citizen.getJob() != null && citizen.getJob().getJobRegistryEntry().getKey().getPath().equals(job),
                    () -> citizen.getJob() == null) + " (" + citizen.getName() + " as " + job + ")";
            if (worker != null && !place.assignCitizen(worker)) {
                result += "; WARNING: " + worker.getName() + " didn't get the job back";
            }
            return result;
        }));
    }

    private void moveHome() {
        checks.add(new Named("move_home", () -> {
            ICitizenData citizen = jobless();
            if (citizen == null) {
                citizen = colonist(null);
            }
            IBuilding before = citizen.getHomeBuilding();
            ICitizenData who = citizen;
            return order("move_home", citizen, args("colonist", citizen.getName(), "home", "residence"), "move to another residence",
                    true, () -> who.getHomeBuilding() != null && who.getHomeBuilding() != before,
                    () -> who.getHomeBuilding() == before) + " (" + citizen.getName() + ")";
        }));
    }

    private void addToMenu() {
        checks.add(new Named("add_to_menu", () -> {
            if (building("cook") == null) {
                return "SKIP, no restaurant";
            }
            ICitizenData citizen = colonist("cook");
            for (String dish : List.of("baked potato", "cooked cod", "pumpkin pie", "cooked rabbit")) {
                JsonObject reply = call("add_to_menu", citizen, args("dish", dish), "put " + dish + " on the menu");
                if (reply.has("waiting_for_yes")) {
                    if (errands.confirm(player) != 1) {
                        return "FAIL, the yes didn't put it on the menu";
                    }
                    return errands.undo(player) == 1 ? "PASS, " + dish + " on and off the menu" : "FAIL, undo didn't work";
                }
            }
            return "FAIL, no dish could go on the menu";
        }));
    }

    private void farmerPlant() {
        checks.add(new Named("farmer_plant", () -> {
            ICitizenData farmer = colonist("farmer");
            if (farmer == null) {
                return "SKIP, no farmer";
            }
            return order("farmer_plant", farmer, args("crop", "carrot"), "plant carrots", true, null, null);
        }));
    }

    private void startResearch() {
        checks.add(new Named("start_research", () -> {
            if (building("university") == null) {
                return "SKIP, no university";
            }
            ICitizenData citizen = colonist(null);
            for (String research : List.of("stamina", "keen", "hot", "agile", "tactic", "outpost", "the flintstones",
                    "bonemeal", "rapid", "deep", "first aid", "lucky")) {
                JsonObject reply = call("start_research", citizen, args("research", research), "research " + research);
                if (reply.has("waiting_for_yes")) {
                    int started = errands.confirm(player);
                    return started == 1 ? "PASS, " + research + " started" : "FAIL, " + research + " didn't start (cost?)";
                }
            }
            return "FAIL, no research could start";
        }));
    }

    /** Something the warehouse holds, to ask the couriers for. */
    private Item stocked() {
        IBuilding warehouse = building("warehouse");
        IItemHandler handler = warehouse == null ? null : warehouse.getItemHandlerCap();
        if (handler == null) {
            return null;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getCount() >= 2 && stack.getComponentsPatch().isEmpty()) {
                return stack.getItem();
            }
        }
        return null;
    }

    private void fetch() {
        Item[] item = new Item[1];
        int[] before = new int[1];
        checks.add(new Named("fetch_item", new Check() {
            @Override
            public String start() {
                ICitizenData courier = colonist("deliveryman");
                item[0] = stocked();
                if (courier == null || item[0] == null) {
                    return "SKIP, no courier or an empty warehouse";
                }
                before[0] = player.getInventory().countItem(item[0]);
                String name = new ItemStack(item[0]).getHoverName().getString();
                String result = order("fetch_item", courier, args("item", name, "count", "1"), "bring me one " + name, false, null, null);
                return result.startsWith("PASS") ? null : result;
            }

            @Override
            public String done() {
                return player.getInventory().countItem(item[0]) > before[0] ? "PASS, the courier brought " + item[0] : null;
            }

            @Override
            public int limit() {
                return WALK;
            }
        }));
    }

    private void deliver() {
        Item[] item = new Item[1];
        int[] before = new int[1];
        IBuilding[] to = new IBuilding[1];
        checks.add(new Named("deliver_item", new Check() {
            @Override
            public String start() {
                ICitizenData courier = colonist("deliveryman");
                item[0] = stocked();
                to[0] = building("cook");
                if (courier == null || item[0] == null || to[0] == null || to[0].getItemHandlerCap() == null) {
                    return "SKIP, no courier, stock or restaurant";
                }
                before[0] = count(to[0], item[0]);
                String name = new ItemStack(item[0]).getHoverName().getString();
                String result = order("deliver_item", courier, args("item", name, "count", "1", "building", "Dining Hall"),
                        "take one " + name + " to the dining hall", false, null, null);
                return result.startsWith("PASS") ? null : result;
            }

            @Override
            public String done() {
                return count(to[0], item[0]) > before[0] ? "PASS, the courier delivered " + item[0] : null;
            }

            @Override
            public int limit() {
                return WALK;
            }
        }));
    }

    private static int count(IBuilding building, Item item) {
        IItemHandler handler = building.getItemHandlerCap();
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private AbstractBuildingGuards tower() {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(b -> b instanceof AbstractBuildingGuards && b.getBuildingLevel() > 0)
                .map(b -> (AbstractBuildingGuards) b).findFirst().orElse(null);
    }

    private static String task(AbstractBuildingGuards tower) {
        return tower.getSetting(AbstractBuildingGuards.GUARD_TASK).getValue();
    }

    private void guards() {
        String[] original = new String[1];
        for (String[] order : List.of(new String[] {"guard_me", GuardTaskSetting.FOLLOW, "guard me"},
                new String[] {"defend_here", GuardTaskSetting.GUARD, "defend here"},
                new String[] {"red_alert", GuardTaskSetting.GUARD, "red alert"})) {
            checks.add(new Named(order[0], () -> {
                AbstractBuildingGuards tower = tower();
                ICitizenData guard = colonist("knight");
                if (tower == null || guard == null) {
                    return "SKIP, no guard tower with a guard";
                }
                original[0] = task(tower);
                String result = order(order[0], guard, args("who", "me"), order[2], false,
                        () -> task(tower).equals(order[1]), null);
                if (!result.startsWith("PASS")) {
                    return result;
                }
                JsonObject down = call("stand_down", guard, new JsonObject(), "stand down");
                if (down.has("waiting_for_yes") && errands.confirm(player) != 1) {
                    return "FAIL, stand_down wasn't carried out";
                }
                return task(tower).equals(original[0]) ? "PASS, " + order[1] + " then back to " + original[0]
                        : "FAIL, stand_down left the tower on " + task(tower) + ", was " + original[0];
            }));
        }
        checks.add(new Named("patrol_here", () -> {
            AbstractBuildingGuards tower = tower();
            ICitizenData guard = colonist("knight");
            if (tower == null || guard == null) {
                return "SKIP, no guard tower with a guard";
            }
            String before = task(tower);
            return order("patrol_here", guard, new JsonObject(), "patrol here", true,
                    () -> task(tower).equals(GuardTaskSetting.PATROL), () -> task(tower).equals(before));
        }));
    }

    private void notifyWhen() {
        int[] seen = new int[1];
        ICitizenData[] who = new ICitizenData[1];
        checks.add(new Named("notify_when", new Check() {
            @Override
            public String start() {
                who[0] = colonist("deliveryman");
                if (who[0] == null) {
                    who[0] = colonist(null);
                }
                JsonObject reply = call("notify_when", who[0], args("item", "diamond", "count", "1"), "tell me when you find a diamond");
                if (!success(reply)) {
                    return "FAIL, " + reply;
                }
                seen[0] = messages();
                // The colonist "finds" a diamond: it turns up in their pockets.
                net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(who[0].getInventory(), new ItemStack(Items.DIAMOND), false);
                return null;
            }

            @Override
            public String done() {
                return messages() > seen[0] ? "PASS, told the player" : null;
            }
        }));
    }

    /** Watches still open; one that told the player is dropped. Read by reflection: dev code doesn't change the mod. */
    private int messages() {
        try {
            var notifyField = Errands.class.getDeclaredField("notify");
            notifyField.setAccessible(true);
            Object notify = notifyField.get(errands);
            var watchesField = notify.getClass().getDeclaredField("watches");
            watchesField.setAccessible(true);
            return -((java.util.Collection<?>) watchesField.get(notify)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void leaveATowerForTheRestart() {
        checks.add(new Named("tower settings across a restart (part 1)", () -> {
            AbstractBuildingGuards tower = tower();
            ICitizenData guard = colonist("knight");
            if (tower == null || guard == null) {
                return "SKIP, no guard tower with a guard";
            }
            String original = task(tower);
            String result = order("guard_me", guard, args("who", "me"), "guard me", false,
                    () -> task(tower).equals(GuardTaskSetting.FOLLOW), null);
            if (!result.startsWith("PASS")) {
                return result;
            }
            Properties state = new Properties();
            state.setProperty("tower", String.valueOf(tower.getPosition().asLong()));
            state.setProperty("task", original);
            try {
                state.store(Files.newBufferedWriter(server.getServerDirectory().resolve(STATE)), "live run errands");
            } catch (IOException e) {
                return "FAIL, " + e;
            }
            return "PASS, left on follow (was " + original + ") for the restart";
        }));
    }

    /** Part 2, after the restart: the tower is back on its own task. */
    static void afterRestart(MinecraftServer server, IColony colony) {
        Path file = server.getServerDirectory().resolve(STATE);
        Properties state = new Properties();
        try {
            state.load(Files.newBufferedReader(file));
        } catch (IOException e) {
            ModLog.info("Live run errands-restart: FAIL, no state from part 1 ({})", e.toString());
            return;
        }
        IBuilding building = colony.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(state.getProperty("tower"))));
        if (!(building instanceof AbstractBuildingGuards tower)) {
            ModLog.info("Live run errands-restart: FAIL, the tower is gone");
            return;
        }
        String now = task(tower);
        String was = state.getProperty("task");
        ModLog.info("Live run errands-restart: {}", now.equals(was) ? "PASS, the tower is back on " + was
                : "FAIL, the tower is on " + now + " after the restart, was " + was);
    }
}
