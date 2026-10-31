package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.talk.Prompts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Collects {@link ColonistFacts} from MineColonies. Server thread only. Every part is read on its
 * own and a failure only drops that part: MineColonies internals change between versions, and a
 * missing detail is better than a colonist who can't talk.
 */
public final class PerceptionService {
    /**
     * Kinds of work players ask for by the work rather than by the hut, and the workplace that does
     * it, by building registry path. From Colonist Errands' {@code WorkTruth} by Lovkar
     * (GPL-3.0-or-later): the Forester's Hut is registered as {@code lumberjack}, not "forester".
     */
    private static final Map<String, String[]> BY_THE_WORK = new LinkedHashMap<>();

    static {
        BY_THE_WORK.put("chopping wood", new String[] {"lumberjack", "the Forester's Hut"});
        BY_THE_WORK.put("mining", new String[] {"miner", "the Mine"});
        BY_THE_WORK.put("farming", new String[] {"farmer", "the Farm"});
        BY_THE_WORK.put("fishing", new String[] {"fisherman", "the Fisher's Hut"});
        BY_THE_WORK.put("herding animals", new String[] {"cowboy", "the Cowhand's Hut"});
        BY_THE_WORK.put("building", new String[] {"builder", "the Builder's Hut"});
    }

    /** Requests someone is already producing or delivering (Colonist Errands' {@code SupplyCheck}). */
    private static final Set<RequestState> UNDERWAY = EnumSet.of(RequestState.ASSIGNED, RequestState.IN_PROGRESS,
            RequestState.RESOLVED, RequestState.FOLLOWUP_IN_PROGRESS, RequestState.FINALIZING);
    /** Requests nobody has picked up. */
    private static final Set<RequestState> WAITING = EnumSet.of(RequestState.CREATED, RequestState.REPORTED,
            RequestState.ASSIGNING);
    private static final double HUNGRY = 8;
    private static final int MAX_LISTED = 3;
    /** Missing building materials listed at most, the largest shortfalls first. */
    private static final int MAX_MATERIALS = 8;

    /** How often what colonists are doing is noted, in ticks (thirty seconds). */
    private static final int DIARY_INTERVAL = 600;
    /** Diary entries shown in a reply prompt. */
    private static final int DIARY_SHOWN = 3;
    /** Materials of each construction as first seen, for "how much is used"; not saved. */
    private final BuildLedger ledger = new BuildLedger();
    /** Each colonist's day as code saw it, by {@code colonyId:citizenId}; not saved, a day is short. */
    private final Map<String, DiaryBook> diaries = new HashMap<>();
    /** Each colonist's job as last seen, by {@code colonyId:citizenId}, for a job given or taken just now. */
    private final JobLog jobs = new JobLog();
    /** The facts last read for each colonist, by {@code colonyId:citizenId}, with the game time. */
    private final Map<String, Cached> recent = new HashMap<>();
    /** How long read facts are kept, in game ticks (five seconds). */
    private static final long CACHE_TICKS = 100;

    private record Cached(long at, ColonistFacts facts) {
    }

    /** The {@code {situation}} block for a reply prompt, at the configured perception level. */
    public String situation(ICitizenData citizen) {
        Level level = Config.level(Module.PERCEPTION);
        if (level == Level.OFF) {
            return "";
        }
        String text = Situation.render(facts(citizen), level);
        List<DiaryBook.Entry> day = diary(citizen, DIARY_SHOWN);
        // Real things the colonist did, so they have something true to tell instead of making it up.
        return day.isEmpty() ? text : text + "\nYour day so far, as background (bring it up only if asked or if it fits): "
                + DiaryBook.text(day) + ".";
    }

    /** The colonist's latest doings of the last game day, oldest first. */
    public List<DiaryBook.Entry> diary(ICitizenData citizen, int count) {
        DiaryBook book = diaries.get(citizen.getColony().getID() + ":" + citizen.getId());
        return book == null || citizen.getColony().getWorld() == null ? List.of()
                : book.latest(citizen.getColony().getWorld().getGameTime(), count);
    }

    /** Notes what each loaded colonist is doing now. Server tick. */
    public void tick(MinecraftServer server) {
        if (server.getTickCount() % DIARY_INTERVAL != 0) {
            return;
        }
        recent.clear();
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getWorld() == null) {
                continue;
            }
            long gameTime = colony.getWorld().getGameTime();
            long dayTime = colony.getWorld().getDayTime();
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                try {
                    JobLog.Change change = jobs.note(colony.getID() + ":" + citizen.getId(), jobTitle(citizen), gameTime);
                    if (change != null) {
                        ModEvents.BUS.post(new GameEvents.JobChanged(citizen, change.from(), change.to()));
                    }
                } catch (RuntimeException e) {
                    // A job MineColonies can't name now is noted next time.
                }
                if (citizen.getEntity().isEmpty()) {
                    continue;
                }
                String doing;
                try {
                    doing = doing(citizen);
                } catch (RuntimeException e) {
                    doing = null;
                }
                if (doing != null) {
                    diaries.computeIfAbsent(colony.getID() + ":" + citizen.getId(), key -> new DiaryBook())
                            .note(gameTime, dayTime, doing);
                }
            }
        }
    }

    public void forget(Predicate<String> key) {
        diaries.keySet().removeIf(key);
        jobs.forget(key);
        recent.keySet().removeIf(key);
    }

    public void clear() {
        diaries.clear();
        jobs.clear();
        recent.clear();
    }

    private static String jobTitle(ICitizenData citizen) {
        return citizen.getJob() == null ? null : Prompts.jobName(citizen.getJob().getJobRegistryEntry().getKey().getPath());
    }

    /** What the colonist is doing, as MineColonies shows it over their head. */
    private static String doing(ICitizenData citizen) {
        VisibleCitizenStatus status = citizen.getStatus();
        IBuilding work = citizen.getWorkBuilding();
        if (status == VisibleCitizenStatus.WORKING && work != null) {
            return "worked at the " + ItemNames.named(Component.translatable(work.getBuildingType().getTranslationKey()));
        }
        if (status == VisibleCitizenStatus.SLEEP) {
            return citizen.getHomeBuilding() != null ? "slept at home" : "slept outside, with no bed of your own";
        }
        if (status == VisibleCitizenStatus.EAT) {
            return "had a meal";
        }
        if (status == VisibleCitizenStatus.SICK) {
            return "lay ill";
        }
        if (status == VisibleCitizenStatus.BAD_WEATHER) {
            return "sheltered from the bad weather";
        }
        if (status == VisibleCitizenStatus.RAIDED) {
            return "hid from the raiders";
        }
        if (status == VisibleCitizenStatus.MOURNING) {
            return "mourned someone who died";
        }
        if (status == VisibleCitizenStatus.HOUSE) {
            return "spent some time at home";
        }
        return work == null && !citizen.isChild() ? "had no work to do" : null;
    }

    /**
     * What the colonist knows of themselves and the colony now. Kept a few seconds (#40): one round of
     * first lines, greetings and look-ups asks for every colonist several times, and a big colony has many.
     */
    public ColonistFacts facts(ICitizenData citizen) {
        String key = citizen.getColony().getID() + ":" + citizen.getId();
        long now = citizen.getColony().getWorld() == null ? 0 : citizen.getColony().getWorld().getGameTime();
        Cached cached = recent.get(key);
        if (cached != null && now >= cached.at() && now - cached.at() < CACHE_TICKS) {
            return cached.facts();
        }
        ColonistFacts facts = read(citizen);
        recent.put(key, new Cached(now, facts));
        return facts;
    }

    private ColonistFacts read(ICitizenData citizen) {
        ColonistFacts.Builder facts = ColonistFacts.builder();
        IColony colony = citizen.getColony();
        safely("job", () -> {
            facts.job(jobTitle(citizen));
            JobLog.Change change = colony.getWorld() == null ? null
                    : jobs.recent(colony.getID() + ":" + citizen.getId(), colony.getWorld().getGameTime());
            if (change != null) {
                facts.newJob(change.to(), change.from());
            }
            IBuilding work = citizen.getWorkBuilding();
            facts.workplaceLevel(work == null ? 0 : work.getBuildingLevel());
            facts.workplaceMaxLevel(work == null ? 0 : work.getMaxBuildingLevel());
        });
        safely("workplaces", () -> workplaces(colony, facts));
        safely("home", () -> {
            IBuilding home = citizen.getHomeBuilding();
            facts.homeLevel(home == null ? 0 : Math.max(1, home.getBuildingLevel()));
        });
        safely("food", () -> {
            facts.saturation(citizen.getSaturation());
            if (citizen.getSaturation() < HUNGRY) {
                facts.food(food(citizen, colony));
            }
        });
        safely("health", () -> citizen.getEntity().ifPresent(entity -> facts.health(entity.getHealth() / entity.getMaxHealth())));
        safely("sickness", () -> {
            if (citizen.getCitizenDiseaseHandler().isSick()) {
                var disease = citizen.getCitizenDiseaseHandler().getDisease();
                facts.sickness(disease == null ? "an illness" : disease.name().getString(),
                        citizen.getCitizenDiseaseHandler().sleepsAtHospital());
            }
        });
        safely("happiness", () -> {
            facts.happiness(citizen.getCitizenHappinessHandler().getHappiness(colony, citizen));
            facts.unhappyAbout(unhappyAbout(citizen));
        });
        safely("requests", () -> requests(citizen, facts));
        safely("construction", () -> construction(citizen, facts, ledger));
        safely("work", () -> facts.workNotes(workNotes(citizen)));
        safely("weather", () -> {
            var world = colony.getWorld();
            facts.weather(world.isThundering() ? "a thunderstorm" : world.isRaining() ? "rain" : "clear");
        });
        safely("colony", () -> {
            List<ICitizenData> all = colony.getCitizenManager().getCitizens();
            int unemployed = (int) all.stream().filter(c -> c.getJob() == null && !c.isChild()).count();
            int homeless = (int) all.stream().filter(c -> c.getHomeBuilding() == null).count();
            facts.colony(all.size(), unemployed, homeless, colony.getRaiderManager().isRaided());
        });
        safely("family", () -> facts.family(family(citizen)));
        safely("coworkers", () -> facts.coworkers(coworkers(citizen)));
        return facts.build();
    }

    private interface Part {
        void read();
    }

    private static void safely(String part, Part reader) {
        try {
            reader.read();
        } catch (RuntimeException | LinkageError e) {
            LivingColonists.LOGGER.debug("Could not read the {} of a colonist: {}", part, e.toString());
        }
    }

    private static void workplaces(IColony colony, ColonistFacts.Builder facts) {
        Set<String> built = new HashSet<>();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (building.getBuildingLevel() > 0 && building.getBuildingType() != null) {
                built.add(building.getBuildingType().getRegistryName().getPath());
            }
        }
        List<String> available = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BY_THE_WORK.forEach((work, hut) -> {
            if (built.contains(hut[0])) {
                available.add(work + " (" + hut[1] + ")");
            } else {
                missing.add(work);
            }
        });
        facts.availableWork(available).missingWork(missing);
    }

    /** Where a hungry colonist can eat without the player, as MineColonies' own eating task looks (Errands' {@code FoodCheck}). */
    private static ColonistFacts.Food food(ICitizenData citizen, IColony colony) {
        AbstractEntityCitizen entity = citizen.getEntity().orElse(null);
        if (entity != null && FoodUtils.getBestFoodForCitizen(entity.getInventoryCitizen(), citizen, null) >= 0) {
            return ColonistFacts.Food.POCKETS;
        }
        BlockPos anchor = citizen.getWorkBuilding() != null ? citizen.getWorkBuilding().getPosition()
                : entity != null ? entity.blockPosition() : null;
        if (anchor == null) {
            return ColonistFacts.Food.UNKNOWN;
        }
        BlockPos restaurant = colony.getServerBuildingManager().getBestBuilding(anchor, BuildingCook.class);
        if (restaurant != null) {
            BuildingCook cook = colony.getServerBuildingManager().getBuilding(restaurant, BuildingCook.class);
            if (cook != null && FoodUtils.checkForFoodInBuilding(citizen, null, cook) != null) {
                return ColonistFacts.Food.RESTAURANT;
            }
        }
        return ColonistFacts.Food.NONE;
    }

    /** The happiness factors furthest below normal, as words: "food", "security"... */
    private static List<String> unhappyAbout(ICitizenData citizen) {
        List<IHappinessModifier> low = new ArrayList<>();
        for (String id : citizen.getCitizenHappinessHandler().getModifiers()) {
            IHappinessModifier modifier = citizen.getCitizenHappinessHandler().getModifier(id);
            if (modifier != null && modifier.getFactor(citizen) < 1) {
                low.add(modifier);
            }
        }
        return low.stream()
                .sorted(Comparator.comparingDouble(m -> m.getFactor(citizen)))
                .limit(MAX_LISTED)
                .map(m -> words(m.getId()))
                .toList();
    }

    /**
     * Open requests of the colonist's workplace, split by who handles them. A request nobody in the
     * colony can fulfil goes to the player resolver (or the retrying one) and shows as ASSIGNED,
     * which looks like "on the way" but means "a player has to bring it".
     */
    private static void requests(ICitizenData citizen, ColonistFacts.Builder facts) {
        IBuilding work = citizen.getWorkBuilding();
        if (work == null) {
            return;
        }
        IRequestManager manager = citizen.getColony().getRequestManager();
        int underway = 0;
        List<String> waiting = new ArrayList<>();
        List<String> onPlayer = new ArrayList<>();
        for (IRequest<?> request : work.getOpenRequests(citizen.getId())) {
            if (request == null) {
                continue;
            }
            if (resolvedByPlayer(manager, request)) {
                if (onPlayer.size() < MAX_LISTED) {
                    onPlayer.add(describe(request));
                }
            } else if (UNDERWAY.contains(request.getState())) {
                underway++;
            } else if (WAITING.contains(request.getState()) && waiting.size() < MAX_LISTED) {
                waiting.add(describe(request));
            }
        }
        facts.requests(underway, waiting).playerMustBring(onPlayer);
    }

    private static boolean resolvedByPlayer(IRequestManager manager, IRequest<?> request) {
        try {
            IRequestResolver<?> resolver = manager.getResolverForRequest(request.getId());
            return resolver instanceof IPlayerRequestResolver || resolver instanceof IRetryingRequestResolver;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String describe(IRequest<?> request) {
        String text = request.getShortDisplayString().getString();
        List<ItemStack> stacks = request.getDisplayStacks();
        // A one-word request is still a request ("Pickaxe" for a tool); only a blank one or a raw translation key
        // tells nothing, and then the item itself is named, if there is one.
        if (text.isBlank() || !text.contains(" ") && text.contains(".")) {
            return stacks.isEmpty() ? "something for work" : ItemNames.of(stacks.get(0));
        }
        // With the item's name in the players' languages, so the model doesn't make one up.
        return stacks.isEmpty() ? text : ItemNames.in(text, stacks.get(0));
    }

    /**
     * What the job involves right now, beyond what every job has: orders the workplace is making
     * for others (crafters, the warehouse, couriers), and a few profession-specific facts.
     */
    private static List<String> workNotes(ICitizenData citizen) {
        List<String> notes = new ArrayList<>();
        IBuilding work = citizen.getWorkBuilding();
        if (work == null) {
            return notes;
        }
        IColony colony = citizen.getColony();
        safely("orders", () -> orders(colony, work, notes));
        safely("fields", () -> {
            int fields = 0;
            if (work.hasModule(BuildingModules.FARMER_FIELDS)) {
                fields = work.getModule(BuildingModules.FARMER_FIELDS).getOwnedExtensions().size();
            } else if (work.hasModule(BuildingModules.PLANTATION_FIELDS)) {
                fields = work.getModule(BuildingModules.PLANTATION_FIELDS).getOwnedExtensions().size();
            } else {
                return;
            }
            notes.add(fields == 0 ? "You have no fields assigned, so you can't grow anything until a player gives you one."
                    : "You tend " + fields + (fields == 1 ? " field." : " fields."));
        });
        safely("mine", () -> {
            if (work.hasModule(BuildingModules.MINER_LEVELS)) {
                int levels = work.getModule(BuildingModules.MINER_LEVELS).getNumberOfLevels();
                notes.add(levels == 0 ? "Your mine has no levels dug yet." : "Your mine has " + levels
                        + (levels == 1 ? " level." : " levels."));
            }
        });
        safely("courier", () -> {
            if (citizen.getJob() instanceof JobDeliveryman courier) {
                int tasks = courier.getTaskQueue().size();
                notes.add(tasks == 0 ? "You have no deliveries queued." : "You have " + tasks
                        + (tasks == 1 ? " delivery queued." : " deliveries queued."));
            }
        });
        safely("hospital", () -> {
            if (work instanceof BuildingHospital hospital) {
                int patients = hospital.getPatients().size();
                notes.add(patients == 0 ? "There are no patients in the hospital." : "There "
                        + (patients == 1 ? "is 1 patient" : "are " + patients + " patients") + " in the hospital.");
            }
        });
        safely("research", () -> {
            if ("researcher".equals(jobPath(citizen))) {
                List<String> research = colony.getResearchManager().getResearchTree().getResearchInProgress().stream()
                        .limit(MAX_LISTED).map(r -> words(r.getId().getPath())).toList();
                notes.add(research.isEmpty() ? "No research is in progress." : "Research in progress: "
                        + String.join(", ", research) + ".");
            }
        });
        return notes;
    }

    /** Requests the colony's request system has handed to this workplace to fulfil. */
    private static void orders(IColony colony, IBuilding work, List<String> notes) {
        if (!(colony.getRequestManager() instanceof IStandardRequestManager manager)) {
            return;
        }
        List<String> orders = new ArrayList<>();
        int total = 0;
        for (IRequestResolver<?> resolver : work.getResolvers()) {
            for (IToken<?> token : manager.getResolverHandler().getRequestsAssignedToResolver(resolver)) {
                total++;
                if (orders.size() < MAX_LISTED) {
                    orders.add(describe(manager.getRequestForToken(token)));
                }
            }
        }
        if (total > 0) {
            notes.add("You are working on " + total + (total == 1 ? " order" : " orders") + " for others: "
                    + String.join(", ", orders) + (total > orders.size() ? " and more" : "") + ".");
        }
    }

    private static String jobPath(ICitizenData citizen) {
        return citizen.getJob() == null ? null : citizen.getJob().getJobRegistryEntry().getKey().getPath();
    }

    /** What a builder is building and which materials it still lacks, as the builder's hut tracks them. */
    private static void construction(ICitizenData citizen, ColonistFacts.Builder facts, BuildLedger ledger) {
        if (!(citizen.getWorkBuilding() instanceof AbstractBuildingStructureBuilder builder)) {
            return;
        }
        if (!builder.hasWorkOrder()) {
            // Orders nobody took yet: their materials aren't counted until a builder starts one.
            facts.waitingOrders(citizen.getColony().getWorkManager().getWorkOrders().values().stream()
                    .filter(order -> !order.isClaimed())
                    .limit(MAX_LISTED)
                    .map(order -> ItemNames.named(order.getDisplayName()) + ", level " + order.getTargetLevel())
                    .toList());
            return;
        }
        var order = builder.getWorkOrder();
        String key = citizen.getColony().getID() + ":" + order.getLocation().toShortString() + ":" + order.getTargetLevel();
        List<BuildLedger.Material> materials = new ArrayList<>();
        for (BuildingBuilderResource resource : builder.getNeededResources().values()) {
            materials.add(new BuildLedger.Material(resource.getName(), resource.getAmount(), resource.getAvailable(),
                    resource.getAmountInDelivery()));
        }
        // What the resource scroll shows: how far along it is, what is used, at hand and on the way.
        List<String> ledgerLines = ledger.lines(key, materials, MAX_MATERIALS);
        int used = ledger.percentUsed(key, materials);
        var progress = builder.getProgress();
        String stage = progress == null || progress.getB() == null ? ""
                : ", stage: " + progress.getB().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String project = ItemNames.named(order.getDisplayName()) + ", level " + order.getTargetLevel() + stage
                + (used > 0 ? ", about " + used + "% of the materials used" : "");
        facts.materialLedger(ledgerLines);
        List<Map.Entry<Integer, String>> lacking = new ArrayList<>();
        int onTheWay = 0;
        for (BuildingBuilderResource resource : builder.getNeededResources().values()) {
            int missing = resource.getAmount() - resource.getAvailable() - resource.getAmountInDelivery();
            if (resource.getAmountInDelivery() > 0) {
                onTheWay++;
            }
            if (missing > 0) {
                lacking.add(Map.entry(missing, missing + " " + resource.getName()));
            }
        }
        List<String> missing = lacking.stream()
                .sorted(Map.Entry.<Integer, String>comparingByKey().reversed())
                .limit(MAX_MATERIALS)
                .map(Map.Entry::getValue)
                .toList();
        facts.construction(project, missing, onTheWay);
    }

    private static List<ColonistFacts.Relative> family(ICitizenData citizen) {
        List<ColonistFacts.Relative> family = new ArrayList<>();
        var manager = citizen.getColony().getCitizenManager();
        ICitizenData partner = citizen.getPartner();
        if (partner != null) {
            family.add(relative(partner.isFemale() ? "wife" : "husband", partner));
        }
        each(citizen.getChildren(), id -> {
            ICitizenData child = manager.getCivilian(id);
            if (child != null) {
                family.add(relative(child.isFemale() ? "daughter" : "son", child));
            }
        });
        Tuple<String, String> parents = citizen.getParents();
        if (parents != null) {
            for (ICitizenData other : manager.getCitizens()) {
                if (other.getName().equals(parents.getA()) || other.getName().equals(parents.getB())) {
                    family.add(relative(other.isFemale() ? "mother" : "father", other));
                }
            }
        }
        each(citizen.getSiblings(), id -> {
            ICitizenData sibling = manager.getCivilian(id);
            if (sibling != null) {
                family.add(relative(sibling.isFemale() ? "sister" : "brother", sibling));
            }
        });
        return family;
    }

    private static List<ColonistFacts.Relative> coworkers(ICitizenData citizen) {
        IBuilding work = citizen.getWorkBuilding();
        if (work == null) {
            return List.of();
        }
        return work.getAllAssignedCitizen().stream()
                .filter(other -> other.getId() != citizen.getId())
                .map(other -> relative("coworker", other))
                .toList();
    }

    private static <T> void each(List<T> items, Consumer<T> action) {
        if (items != null) {
            items.forEach(action);
        }
    }

    private static ColonistFacts.Relative relative(String relation, ICitizenData other) {
        List<String> notes = new ArrayList<>();
        if (other.isChild()) {
            notes.add("a child");
        } else if (other.getJob() == null) {
            notes.add("no job");
        }
        if (other.getSaturation() < HUNGRY) {
            notes.add("hungry");
        }
        if (other.getHomeBuilding() == null) {
            notes.add("no home");
        }
        try {
            if (other.getCitizenDiseaseHandler().isSick()) {
                notes.add("sick");
            }
            if (other.getCitizenHappinessHandler().getHappiness(other.getColony(), other) < 4) {
                notes.add("unhappy");
            }
        } catch (RuntimeException e) {
            // A detail less; the relative is still worth mentioning.
        }
        String job = other.getJob() == null ? null : Prompts.jobName(other.getJob().getJobRegistryEntry().getKey().getPath());
        return new ColonistFacts.Relative(relation, other.getName(), job, notes);
    }

    /** "homeliness" → "homeliness", "idleatjob" stays, "sleptTonight" → "slept tonight". */
    private static String words(String id) {
        return id.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
