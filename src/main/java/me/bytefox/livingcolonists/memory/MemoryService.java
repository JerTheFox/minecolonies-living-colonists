package me.bytefox.livingcolonists.memory;

import me.bytefox.livingcolonists.bonds.PlayerMarks;
import java.util.ArrayList;
import java.util.Collection;
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
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.llm.EmbeddingClient;
import me.bytefox.livingcolonists.llm.LlmEndpoint;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.talk.Addressing;
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
    /** Facts of what a colonist did on a player's order. */
    private static final String DEED = "deed";
    /** As important as a plan or a decision: kept for a few days. */
    private static final int DEED_IMPORTANCE = 3;
    /** Colony chat lines older than this (one Minecraft day) are dropped. */
    private static final long COLONY_MAX_AGE = 24000;
    private static final long DAY = 24000;
    /** How often conversations are checked for writing down, in server ticks. */
    private static final int CHECK_INTERVAL = 200;
    /**
     * Token room for writing a conversation down: DeepSeek's reasoning takes 900 to 1800 tokens here
     * and sometimes ran out of the usual 2000, leaving an empty answer.
     */
    static final int DIGEST_TOKENS = 8000;
    /** Facts shown in a reply prompt at most: the ones the talk touches, see {@link Digests#relevant}. */
    private static final int MEMORIES_SHOWN = 8;
    /** Players named in the hint about former names, at most. */
    private static final int MAX_NAME_HINTS = 4;
    /** How long a reply waits for the vector of what was said; then the facts are picked by words. */
    private static final Duration QUERY_WAIT = Duration.ofSeconds(2);
    /** The end of what is said that a vector is made of: the last line or two is what the reply answers. */
    private static final int QUERY_CHARS = 400;

    private final LlmClient client;
    private final EmbeddingClient embeddings;
    private final PromptLibrary prompts;
    private final BondsService bonds;
    private MemoryData data;
    private final FactVectors vectors;
    private final StoryWriter stories;
    private final Digester digester;
    private long lastCleanupDay = -1;

    public MemoryService(LlmClient client, EmbeddingClient embeddings, PromptLibrary prompts, BondsService bonds) {
        this.client = client;
        this.embeddings = embeddings;
        this.vectors = new FactVectors(embeddings);
        this.stories = new StoryWriter(this, client, prompts);
        this.digester = new Digester(this, client, prompts, bonds);
        this.prompts = prompts;
        this.bonds = bonds;
    }

    public void attach(MemoryData data) {
        this.data = data;
        vectors.clear();
        lastCleanupDay = -1;
        PlayerMarks.clearCurrent();
        data.playerNames.forEach((player, names) -> {
            if (!names.isEmpty()) {
                PlayerMarks.setCurrent(player, names.get(0));
            }
        });
    }

    /**
     * Writes down conversations with lines not yet noted: those that went quiet, or all of them with
     * {@code force}. Returns how many requests were started.
     */
    public int digest(MinecraftServer server, boolean force) {
        return digester.digest(server, force);
    }

    FactVectors vectors() {
        return vectors;
    }

    MemoryData data() {
        return data;
    }

    public void detach() {
        data = null;
        vectors.clear();
        PlayerMarks.clearCurrent();
        digester.clear();
    }

    // ---- players

    /**
     * Notes the name colonists know a player by now, with their nickname and the forms set in
     * {@code playerNames}; names from before stay known, so older memories still find the player.
     */
    public void seen(UUID player, String name, String nickname) {
        PlayerMarks.setCurrent(player, name);
        if (data == null) {
            return;
        }
        List<String> names = data.playerNames.computeIfAbsent(player, id -> new ArrayList<>());
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        updated.add(name);
        updated.addAll(Config.playerNames().getOrDefault(nickname, List.of()));
        updated.add(nickname);
        updated.addAll(names);
        if (!List.copyOf(updated).equals(names)) {
            names.clear();
            names.addAll(updated);
            data.setDirty();
        }
    }

    /** Every name the player was known by, the current one first; empty if never seen. */
    public List<String> namesOf(UUID player) {
        return data == null ? List.of() : List.copyOf(data.playerNames.getOrDefault(player, List.of()));
    }

    /** For a prompt: what else these players were called, like "Max (also called Steve42)"; empty if nothing. */
    public String formerNames(Collection<UUID> players) {
        List<String> hints = new ArrayList<>();
        for (UUID player : players) {
            List<String> names = namesOf(player);
            if (names.size() > 1 && hints.size() < MAX_NAME_HINTS) {
                hints.add(names.get(0) + " (also called " + String.join(", ", names.subList(1, names.size())) + ")");
            }
        }
        return hints.isEmpty() ? "" : "The same people under other names: " + String.join("; ", hints) + ".";
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

    /**
     * The colonist's facts as a prompt block, those this talk touches; empty when they remember
     * nothing or memory is off.
     *
     * @param present whom the talk is with: {@link Fact#aboutPlayer}, {@link Fact#aboutCitizen}
     * @param context what is being said
     */
    public String memories(ICitizenData citizen, Set<String> present, String context) {
        return memories(citizen, present, context, Map.of());
    }

    /**
     * The same, found by meaning too when an embeddings API is set up: {@code done} gets the block on
     * the server thread, after a moment for the vector of what was said, or at once without one.
     */
    public void memoriesFor(ICitizenData citizen, Set<String> present, String context, MinecraftServer server,
                            Consumer<String> done) {
        Optional<LlmEndpoint> endpoint = Config.embeddings();
        List<Fact> facts = facts(citizen);
        if (endpoint.isEmpty() || facts.size() <= MEMORIES_SHOWN || context.isBlank()) {
            done.accept(memories(citizen, present, context));
            return;
        }
        vectors.useModel(endpoint.get());
        vectors.embed(facts, endpoint.get(), server);
        String query = context.length() > QUERY_CHARS ? context.substring(context.length() - QUERY_CHARS) : context;
        int colonyId = citizen.getColony().getID();
        int citizenId = citizen.getId();
        embeddings.embed(endpoint.get(), List.of(PlayerMarks.resolve(query)), QUERY_WAIT).whenComplete((result, error) ->
                server.execute(() -> {
                    IColony colony = Citizens.colony(colonyId);
                    ICitizenData current = colony == null ? null : colony.getCitizenManager().getCivilian(citizenId);
                    if (current == null) {
                        done.accept("");
                        return;
                    }
                    Map<Fact, Double> closeness = new HashMap<>();
                    if (error == null && data != null) {
                        for (Fact fact : facts(current)) {
                            float[] vector = vectors.get(fact.text());
                            if (vector != null) {
                                closeness.put(fact, EmbeddingClient.cosine(result.get(0), vector));
                            }
                        }
                    }
                    done.accept(memories(current, present, context, closeness));
                }));
    }

    private String memories(ICitizenData citizen, Set<String> present, String context, Map<Fact, Double> closeness) {
        long now = now(citizen.getColony());
        String facts = Digests.memories(Digests.relevant(facts(citizen), present, context, now, MEMORIES_SHOWN, closeness),
                now);
        List<UUID> players = present.stream().filter(key -> key.startsWith("p:"))
                .map(key -> UUID.fromString(key.substring(2))).toList();
        String names = formerNames(players);
        MemoryData.Story story = data == null ? null : data.stories.get(key(citizen));
        return LifeStories.withStory(names.isEmpty() ? facts : facts.isEmpty() ? names : facts + "\n" + names,
                story == null ? null : PlayerMarks.resolve(story.text()));
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
    public Optional<Fact> rumor(ICitizenData citizen, UUID listener, Random random) {
        if (random.nextDouble() >= FactLimits.of(level()).rumorChance()) {
            return Optional.empty();
        }
        List<String> names = listener == null ? List.of() : namesOf(listener);
        return Digests.pickRumor(facts(citizen), fact -> listener != null && fact.toldBy(listener, names),
                Fact.aboutCitizen(citizen.getId()), citizen.getName(), random);
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
        data.stories.remove(key(citizen));
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
        digester.digest(server, false);
        stories.tick(server, data);
    }


    /**
     * Something the colonist did on a player's order becomes a fact they remember, so they won't
     * later deny it ("I sorted the warehouse for Alex this morning").
     */
    public void rememberDeed(ICitizenData citizen, String deed) {
        if (data != null && FactLimits.of(level()).facts() > 0) {
            store(citizen.getColony(), List.of(citizen.getId()),
                    List.of(new Digests.Entry(DEED_IMPORTANCE, citizen.getName(), deed)), DEED);
            data.setDirty();
        }
    }

    /**
     * Something colonists saw happen ("Alex left while the Tavern was still being built", "Ann became
     * the cook"), for the colonists given. Like any fact it replaces a similar one instead of piling up.
     */
    public void rememberSeen(IColony colony, List<Integer> colonists, String about, String text, int importance) {
        if (data != null && FactLimits.of(level()).facts() > 0) {
            store(colony, colonists, List.of(new Digests.Entry(importance, about, text)), DEED);
            data.setDirty();
        }
    }

    /**
     * Gives the facts to the colonists who heard them. From the colony chat, a fact about one of the
     * colonists who took part goes to that colonist only: the others heard it, but if each of them
     * kept "Ephraim's knee aches", they would all bring it up for days.
     */
    void store(IColony colony, List<Integer> ownerIds, List<Digests.Entry> entries, String source) {
        FactLimits limits = FactLimits.of(level());
        long now = now(colony);
        Set<FactBook> touched = new HashSet<>();
        for (Digests.Entry entry : entries) {
            String text = Digests.cleanFact(PlayerMarks.markNames(entry.text(), data.playerNames));
            if (text.isEmpty()) {
                continue;
            }
            String about = about(colony, entry.about());
            List<Integer> owners = Fact.COLONY_CHAT.equals(source) && about.startsWith("c:")
                    && ownerIds.contains(Integer.parseInt(about.substring(2)))
                    ? List.of(Integer.parseInt(about.substring(2))) : ownerIds;
            for (int id : owners) {
                ICitizenData citizen = colony.getCitizenManager().getCivilian(id);
                if (citizen == null) {
                    continue;
                }
                FactBook book = data.facts.computeIfAbsent(key(citizen), k -> new FactBook());
                book.add(new Fact(text, entry.importance(), now, now + limits.lifetime(entry.importance()), source, false,
                        about));
                touched.add(book);
            }
        }
        touched.forEach(book -> book.cleanup(now, limits.facts()));
    }

    /** Whom the model said a fact is about, as a key: a colonist of this colony, a known player, the colony. */
    private String about(IColony colony, String said) {
        String name = said.strip();
        if (name.isEmpty()) {
            return "";
        }
        if (name.equalsIgnoreCase("colony") || name.equalsIgnoreCase("everyone") || name.equalsIgnoreCase("the colony")) {
            return Fact.ABOUT_COLONY;
        }
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        var colonist = Addressing.mentioned(name, citizens.stream().map(ICitizenData::getName).toList());
        if (colonist.isPresent()) {
            return Fact.aboutCitizen(citizens.get(colonist.getAsInt()).getId());
        }
        for (Map.Entry<UUID, List<String>> player : data.playerNames.entrySet()) {
            if (player.getValue().stream().anyMatch(known -> PlayerMarks.isForm(name, known))) {
                return Fact.aboutPlayer(player.getKey());
            }
        }
        return "";
    }

    /** Forgets expired and surplus facts. */
    private void cleanup() {
        FactLimits limits = FactLimits.of(level());
        data.facts.entrySet().removeIf(entry -> {
            IColony colony = Citizens.colony(Integer.parseInt(entry.getKey().split(":")[0]));
            if (colony == null) {
                return false;
            }
            entry.getValue().cleanup(now(colony), limits.facts());
            return entry.getValue().isEmpty();
        });
        long privateAge = MemoryLimits.of(level()).maxAgeTicks();
        data.privateTalks.entrySet().removeIf(entry -> stale(entry.getValue(),
                Citizens.colony(Integer.parseInt(entry.getKey().split(":")[0])), privateAge));
        data.colonyChats.entrySet().removeIf(entry -> stale(entry.getValue(), Citizens.colony(entry.getKey()),
                COLONY_MAX_AGE));
        data.setDirty();
    }

    /** A conversation with every line written down and too old to remember: it only takes up space. */
    private static boolean stale(Transcript transcript, IColony colony, long maxAge) {
        return colony != null && transcript.undigested().isEmpty()
                && colony.getWorld().getGameTime() - transcript.lastTime() > maxAge;
    }

    /** Forgets the talks and facts of colonists whose key matches (gone colonists, a deleted colony). */
    public void forget(Predicate<String> key) {
        if (data == null) {
            return;
        }
        int before = data.facts.size() + data.privateTalks.size();
        List<String> gone = data.facts.keySet().stream().filter(key).toList();
        data.facts.keySet().removeIf(key);
        data.stories.keySet().removeIf(key);
        data.privateTalks.keySet().removeIf(key);
        // Nobody keeps remembering things about a colonist who is gone; their id may be given to a newcomer.
        boolean forgotAbout = false;
        for (String citizen : gone) {
            String[] parts = citizen.split(":");
            String prefix = parts[0] + ":";
            for (Map.Entry<String, FactBook> book : data.facts.entrySet()) {
                if (book.getKey().startsWith(prefix) && book.getValue().forgetAbout(Fact.aboutCitizen(Integer.parseInt(parts[1])))) {
                    forgotAbout = true;
                }
            }
        }
        if (forgotAbout) {
            data.setDirty();
        }
        if (data.facts.size() + data.privateTalks.size() != before) {
            data.setDirty();
        }
    }

    /** Colonists who died lately, waiting in case they are brought back. */
    public Graves graves() {
        return data == null ? new Graves() : data.graves;
    }

    /** The graves changed: save them with the world. */
    public void gravesChanged() {
        if (data != null) {
            data.setDirty();
        }
    }

    /** A colonist brought back from the dead keeps their memories, life story and private talks under the new key. */
    public void move(String from, String to) {
        if (data == null) {
            return;
        }
        boolean moved = me.bytefox.livingcolonists.Citizens.move(data.facts, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.stories, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.privateTalks, from, to);
        if (moved) {
            data.setDirty();
        }
    }

    /** Forgets a deleted colony's chat; its colonists go through {@link #forget}. */
    public void forgetColonyChat(int colonyId) {
        if (data != null && data.colonyChats.remove(colonyId) != null) {
            data.setDirty();
        }
    }

    static String jobOf(ICitizenData citizen) {
        return Prompts.jobName(citizen.getJob() == null ? null : citizen.getJob().getJobRegistryEntry().getKey().getPath());
    }

    static Level level() {
        return Config.level(Module.MEMORY);
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
