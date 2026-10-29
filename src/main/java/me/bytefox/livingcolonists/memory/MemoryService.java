package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import me.bytefox.livingcolonists.talk.Prompts;
import net.minecraft.server.MinecraftServer;

/**
 * What colonists remember. Short memory: the lines of private talks and of the colony chat. Long
 * memory: facts the character model writes down from those lines once a conversation goes quiet,
 * kept by importance and age, and cleaned up once a game day. How much is kept follows the memory
 * level; the colony chat always keeps a few recent lines, because a conversation thread can't go
 * on without them.
 *
 * <p>Server thread only. Writing facts down runs on the HTTP client's threads with copies of the
 * lines; the result comes back through {@link MinecraftServer#execute}.</p>
 */
public final class MemoryService {
    /** Colony chat lines kept for conversation threads. */
    private static final int COLONY_LINES = 16;
    /** Colony chat lines older than this (one Minecraft day) are dropped. */
    private static final long COLONY_MAX_AGE = 24000;
    private static final long DAY = 24000;
    /** A conversation counts as over after this long without a line (one minute), and is written down. */
    private static final long QUIET_TICKS = 1200;
    /** The colony chat is written down earlier when this many lines wait, before old ones drop off. */
    private static final int COLONY_BACKLOG = 12;
    /** How often conversations are checked for writing down, in server ticks. */
    private static final int CHECK_INTERVAL = 200;
    /** Conversations written down at most per check, so a backlog doesn't burst into requests. */
    private static final int MAX_DIGESTS_PER_CHECK = 4;
    /** After a failed request, wait this long before trying the same conversation again (five minutes). */
    private static final long RETRY_TICKS = 6000;
    /** Facts shown to the model as already known, so it doesn't write them again. */
    private static final int KNOWN_IN_DIGEST = 20;
    /** Already noted lines shown before the new ones, for context. */
    private static final int CONTEXT_LINES = 4;
    private static final int MAX_PRIVATE_FACTS = 5;
    private static final int MAX_COLONY_FACTS = 8;

    private final LlmClient client;
    private final PromptLibrary prompts;
    private MemoryData data;
    /** Conversations being written down, by transcript key. */
    private final Set<String> digesting = new HashSet<>();
    /** Server tick before which a conversation is not tried again, by transcript key. */
    private final Map<String, Long> retryAt = new HashMap<>();
    private long lastCleanupDay = -1;

    public MemoryService(LlmClient client, PromptLibrary prompts) {
        this.client = client;
        this.prompts = prompts;
    }

    public void attach(MemoryData data) {
        this.data = data;
        lastCleanupDay = -1;
    }

    public void detach() {
        data = null;
        digesting.clear();
        retryAt.clear();
    }

    // ---- short memory

    public List<MemoryLine> privateTalk(ICitizenData citizen, UUID player) {
        MemoryLimits limits = MemoryLimits.of(level());
        Transcript transcript = data == null || limits.lines() == 0 ? null : data.privateTalks.get(key(citizen, player));
        return transcript == null ? List.of() : transcript.lines(now(citizen.getColony()), limits.maxAgeTicks());
    }

    public void rememberPrivate(ICitizenData citizen, UUID player, String speaker, String text) {
        MemoryLimits limits = MemoryLimits.of(level());
        if (data == null || limits.lines() == 0) {
            return;
        }
        data.privateTalks.computeIfAbsent(key(citizen, player), k -> new Transcript())
                .add(new MemoryLine(now(citizen.getColony()), speaker, text), limits.lines(), limits.maxAgeTicks());
        data.setDirty();
    }

    public List<MemoryLine> colonyChat(IColony colony) {
        Transcript transcript = data == null ? null : data.colonyChats.get(colony.getID());
        return transcript == null ? List.of() : transcript.lines(now(colony), COLONY_MAX_AGE);
    }

    public void rememberColony(IColony colony, String speaker, String text) {
        if (data == null) {
            return;
        }
        data.colonyChats.computeIfAbsent(colony.getID(), k -> new Transcript())
                .add(new MemoryLine(now(colony), speaker, text), COLONY_LINES, COLONY_MAX_AGE);
        data.setDirty();
    }

    // ---- long memory

    /** The colonist's facts as a prompt block; empty when they remember nothing or memory is off. */
    public String memories(ICitizenData citizen) {
        return Digests.memories(facts(citizen), now(citizen.getColony()));
    }

    /** Facts the colonist still remembers, oldest first. */
    public List<Fact> facts(ICitizenData citizen) {
        FactBook book = data == null || FactLimits.of(level()).facts() == 0 ? null : data.facts.get(key(citizen));
        if (book == null) {
            return List.of();
        }
        long now = now(citizen.getColony());
        return book.all().stream().filter(fact -> fact.expires() >= now).toList();
    }

    /**
     * Something to pass on as gossip this time, if the dice say so: a fact from someone else's private
     * talk. Rolled by the code per line, because a model told to gossip "sometimes" does it every time.
     *
     * @param listener the player being answered, who won't hear their own words back; null in the colony chat
     */
    public Optional<Fact> rumor(ICitizenData citizen, String listener, Random random) {
        if (random.nextDouble() >= FactLimits.of(level()).rumorChance()) {
            return Optional.empty();
        }
        return Digests.pickRumor(facts(citizen), listener, random);
    }

    /** The colonist passed the fact on, so it won't be retold. */
    public void markTold(ICitizenData citizen, Fact fact) {
        FactBook book = data == null ? null : data.facts.get(key(citizen));
        if (book != null && book.markTold(fact)) {
            data.setDirty();
        }
    }

    /** Forgets the colonist's private talks and facts; returns how many talks and facts were dropped. */
    public int forget(ICitizenData citizen) {
        if (data == null) {
            return 0;
        }
        String prefix = key(citizen) + ":";
        int before = data.privateTalks.size();
        data.privateTalks.keySet().removeIf(key -> key.startsWith(prefix));
        FactBook book = data.facts.remove(key(citizen));
        data.setDirty();
        return before - data.privateTalks.size() + (book == null ? 0 : book.all().size());
    }

    /** All private talks of the colonist, by player UUID, for {@code /lcdebug memory}. */
    public Map<String, List<MemoryLine>> privateTalks(ICitizenData citizen) {
        Map<String, List<MemoryLine>> talks = new TreeMap<>();
        if (data == null) {
            return talks;
        }
        String prefix = key(citizen) + ":";
        data.privateTalks.forEach((key, transcript) -> {
            if (key.startsWith(prefix)) {
                talks.put(key.substring(prefix.length()), transcript.all());
            }
        });
        return talks;
    }

    /** Writes down quiet conversations and cleans up once a game day. Server tick. */
    public void tick(MinecraftServer server) {
        if (data == null || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        long day = server.overworld().getGameTime() / DAY;
        if (day != lastCleanupDay) {
            lastCleanupDay = day;
            cleanup();
        }
        digest(server, false);
    }

    /**
     * Writes down conversations with lines not yet noted: those that went quiet, or all of them with
     * {@code force}. Returns how many requests were started.
     */
    public int digest(MinecraftServer server, boolean force) {
        if (data == null || FactLimits.of(level()).facts() == 0 || Config.llmSettings(ModelRole.CHARACTER).isEmpty()) {
            return 0;
        }
        int started = 0;
        int budget = force ? Integer.MAX_VALUE : MAX_DIGESTS_PER_CHECK;
        for (Map.Entry<String, Transcript> entry : List.copyOf(data.privateTalks.entrySet())) {
            if (started >= budget) {
                return started;
            }
            if (digestPrivate(server, entry.getKey(), entry.getValue(), force)) {
                started++;
            }
        }
        for (Map.Entry<Integer, Transcript> entry : List.copyOf(data.colonyChats.entrySet())) {
            if (started >= budget) {
                return started;
            }
            if (digestColony(server, entry.getKey(), entry.getValue(), force)) {
                started++;
            }
        }
        return started;
    }

    private boolean digestPrivate(MinecraftServer server, String key, Transcript transcript, boolean force) {
        List<MemoryLine> lines = transcript.undigested();
        if (lines.isEmpty() || !ready(server, key, force)) {
            return false;
        }
        String[] parts = key.split(":");
        IColony colony = colony(Integer.parseInt(parts[0]));
        ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
        if (citizen == null) {
            return false;
        }
        long now = now(colony);
        boolean full = lines.size() >= Math.max(2, MemoryLimits.of(level()).lines() - 1);
        if (!force && !full && now - transcript.lastTime() < QUIET_TICKS) {
            return false;
        }
        String name = citizen.getName();
        Optional<String> player = transcript.all().stream().map(MemoryLine::speaker)
                .filter(speaker -> !speaker.equals(name)).reduce((first, second) -> second);
        if (player.isEmpty()) {
            transcript.markDigested(transcript.lastTime());
            return false;
        }
        Map<String, String> values = new HashMap<>();
        values.put("who", name + " (" + jobOf(citizen) + ")");
        values.put("where", "a private talk between " + name + " and the player " + player.get());
        values.put("known", known(List.of(citizen)));
        return request(server, key, transcript, values, lines, MAX_PRIVATE_FACTS, colony, List.of(citizen),
                Fact.privateTalk(player.get()));
    }

    private boolean digestColony(MinecraftServer server, int colonyId, Transcript transcript, boolean force) {
        List<MemoryLine> lines = transcript.undigested();
        String key = "colony:" + colonyId;
        if (lines.isEmpty() || !ready(server, key, force)) {
            return false;
        }
        IColony colony = colony(colonyId);
        if (colony == null) {
            return false;
        }
        long now = now(colony);
        if (!force && lines.size() < COLONY_BACKLOG && now - transcript.lastTime() < QUIET_TICKS) {
            return false;
        }
        Set<String> speakers = lines.stream().map(MemoryLine::speaker).collect(Collectors.toSet());
        List<ICitizenData> participants = colony.getCitizenManager().getCitizens().stream()
                .filter(citizen -> speakers.contains(citizen.getName())).toList();
        if (participants.isEmpty()) {
            transcript.markDigested(transcript.lastTime());
            return false;
        }
        Map<String, String> values = new HashMap<>();
        values.put("who", "the colonists who took part (" + participants.stream()
                .map(citizen -> citizen.getName() + ", " + jobOf(citizen)).collect(Collectors.joining("; ")) + ")");
        values.put("where", "the colony's group chat, where players and colonists talk");
        values.put("known", known(participants));
        return request(server, key, transcript, values, lines, MAX_COLONY_FACTS, colony, participants, Fact.COLONY_CHAT);
    }

    /**
     * Asks the character model to write the lines down, and gives the facts to every colonist in
     * {@code owners}. Returns whether the request started.
     */
    private boolean request(MinecraftServer server, String key, Transcript transcript, Map<String, String> values,
                            List<MemoryLine> lines, int maxFacts, IColony colony, List<ICitizenData> owners,
                            String source) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHARACTER);
        FactLimits limits = FactLimits.of(level());
        if (settings.isEmpty()) {
            return false;
        }
        int max = Math.min(maxFacts, limits.facts());
        values.put("colony", colony.getName());
        values.put("context", text(transcript.digestedTail(CONTEXT_LINES), "(none)"));
        values.put("lines", text(lines, ""));
        values.put("max", String.valueOf(max));
        String prompt = Templates.render(prompts.get(PromptLibrary.MEMORY), values);
        long mark = lines.get(lines.size() - 1).gameTime();
        int colonyId = colony.getID();
        List<Integer> ownerIds = owners.stream().map(ICitizenData::getId).toList();
        MemoryData target = data;
        digesting.add(key);
        client.complete(settings.get(), List.of(ChatMessage.user(prompt))).thenAccept(result -> server.execute(() -> {
            digesting.remove(key);
            if (data != target) {
                return;
            }
            switch (result) {
                case ChatResult.Reply reply -> {
                    IColony now = colony(colonyId);
                    if (now == null) {
                        return;
                    }
                    List<Digests.Entry> entries = Digests.parse(reply.text(), max);
                    store(now, ownerIds, entries, source);
                    transcript.markDigested(mark);
                    retryAt.remove(key);
                    data.setDirty();
                    LivingColonists.LOGGER.info("Colonists remembered {} facts from {}", entries.size(),
                            key.startsWith("colony:") ? "the colony chat of " + now.getName() : "a private talk");
                }
                case ChatResult.Failure failure -> {
                    retryAt.put(key, (long) server.getTickCount() + RETRY_TICKS);
                    LivingColonists.LOGGER.warn("Could not write down a conversation, will retry later: {}",
                            failure.reason());
                }
            }
        }));
        return true;
    }

    private void store(IColony colony, List<Integer> ownerIds, List<Digests.Entry> entries, String source) {
        FactLimits limits = FactLimits.of(level());
        long now = now(colony);
        for (int id : ownerIds) {
            ICitizenData citizen = colony.getCitizenManager().getCivilian(id);
            if (citizen == null) {
                continue;
            }
            FactBook book = data.facts.computeIfAbsent(key(citizen), k -> new FactBook());
            for (Digests.Entry entry : entries) {
                String text = Digests.cleanFact(entry.text());
                if (!text.isEmpty()) {
                    book.add(new Fact(text, entry.importance(), now, now + limits.lifetime(entry.importance()), source,
                            false));
                }
            }
            book.cleanup(now, limits.facts());
        }
    }

    private boolean ready(MinecraftServer server, String key, boolean force) {
        return !digesting.contains(key) && (force || retryAt.getOrDefault(key, Long.MIN_VALUE) <= server.getTickCount());
    }

    /** Forgets expired and surplus facts, and everything of colonists who are gone. */
    private void cleanup() {
        FactLimits limits = FactLimits.of(level());
        data.facts.entrySet().removeIf(entry -> {
            String[] parts = entry.getKey().split(":");
            IColony colony = colony(Integer.parseInt(parts[0]));
            if (colony == null) {
                return false;
            }
            if (colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1])) == null) {
                return true;
            }
            entry.getValue().cleanup(now(colony), limits.facts());
            return entry.getValue().isEmpty();
        });
        data.privateTalks.keySet().removeIf(key -> {
            String[] parts = key.split(":");
            IColony colony = colony(Integer.parseInt(parts[0]));
            return colony != null && colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1])) == null;
        });
        data.setDirty();
    }

    private String known(List<ICitizenData> citizens) {
        Set<String> facts = new LinkedHashSet<>();
        for (ICitizenData citizen : citizens) {
            facts(citizen).forEach(fact -> facts.add(fact.text()));
        }
        List<String> recent = new ArrayList<>(facts);
        recent = recent.subList(Math.max(0, recent.size() - KNOWN_IN_DIGEST), recent.size());
        return recent.isEmpty() ? "(nothing yet)" : recent.stream().map(fact -> "- " + fact).collect(Collectors.joining("\n"));
    }

    private static String text(List<MemoryLine> lines, String empty) {
        return lines.isEmpty() ? empty
                : lines.stream().map(line -> "<" + line.speaker() + "> " + line.text()).collect(Collectors.joining("\n"));
    }

    private static String jobOf(ICitizenData citizen) {
        return Prompts.jobName(citizen.getJob() == null ? null : citizen.getJob().getJobRegistryEntry().getKey().getPath());
    }

    private static Level level() {
        return Config.level(Module.MEMORY);
    }

    /**
     * A loaded colony by id. Colony ids are unique per dimension; keys don't carry it, so the first
     * match wins.
     */
    private static IColony colony(int id) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getID() == id && colony.getWorld() != null) {
                return colony;
            }
        }
        return null;
    }

    private static String key(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }

    private static String key(ICitizenData citizen, UUID player) {
        return key(citizen) + ":" + player;
    }

    private static long now(IColony colony) {
        return colony.getWorld().getGameTime();
    }
}
