package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.bonds.Bonds;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Promise;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.EmbeddingClient;
import me.bytefox.livingcolonists.llm.LlmEndpoint;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
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
    /**
     * Token room for writing a conversation down: DeepSeek's reasoning takes 900 to 1800 tokens here
     * and sometimes ran out of the usual 2000, leaving an empty answer.
     */
    private static final int DIGEST_TOKENS = 8000;
    /** Facts shown in a reply prompt at most: the ones the talk touches, see {@link Digests#relevant}. */
    private static final int MEMORIES_SHOWN = 8;
    /** Players named in the hint about former names, at most. */
    private static final int MAX_NAME_HINTS = 4;
    /** How long a reply waits for the vector of what was said; then the facts are picked by words. */
    private static final Duration QUERY_WAIT = Duration.ofSeconds(2);
    private static final Duration FACTS_WAIT = Duration.ofSeconds(20);
    /** Vectors kept in memory, by fact text; rebuilt after a restart, never saved with the world. */
    private static final int MAX_VECTORS = 20000;
    /** The end of what is said that a vector is made of: the last line or two is what the reply answers. */
    private static final int QUERY_CHARS = 400;
    private static final int MAX_PRIVATE_FACTS = 5;
    private static final int MAX_COLONY_FACTS = 4;

    private final LlmClient client;
    private final EmbeddingClient embeddings;
    private final PromptLibrary prompts;
    private final BondsService bonds;
    private MemoryData data;
    private final Map<String, float[]> vectors = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_VECTORS;
        }
    };
    /** Fact texts whose vectors are on their way. */
    private final Set<String> embedding = new HashSet<>();
    /** The model the vectors were made with: another model's vectors don't compare. */
    private String vectorModel = "";
    /** Conversations being written down, by transcript key. */
    private final Set<String> digesting = new HashSet<>();
    /** Server tick before which a conversation is not tried again, by transcript key. */
    private final Map<String, Long> retryAt = new HashMap<>();
    private long lastCleanupDay = -1;

    public MemoryService(LlmClient client, EmbeddingClient embeddings, PromptLibrary prompts, BondsService bonds) {
        this.client = client;
        this.embeddings = embeddings;
        this.prompts = prompts;
        this.bonds = bonds;
    }

    public void attach(MemoryData data) {
        this.data = data;
        lastCleanupDay = -1;
        PlayerMarks.clearCurrent();
        data.playerNames.forEach((player, names) -> {
            if (!names.isEmpty()) {
                PlayerMarks.setCurrent(player, names.get(0));
            }
        });
    }

    public void detach() {
        data = null;
        vectors.clear();
        embedding.clear();
        PlayerMarks.clearCurrent();
        digesting.clear();
        retryAt.clear();
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
        useModel(endpoint.get());
        embedFacts(facts, endpoint.get(), server);
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
        return names.isEmpty() ? facts : facts.isEmpty() ? names : facts + "\n" + names;
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
        return Digests.pickRumor(facts(citizen), fact -> listener != null && fact.toldBy(listener, names), random);
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
        IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
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
        UUID playerId = UUID.fromString(parts[2]);
        List<Promise> open = bonds.open(citizen, playerId);
        values.put("rules", Templates.render(prompts.get(PromptLibrary.MEMORY_PLAYER),
                Map.of("player", player.get(), "name", name, "promises", Bonds.numbered(open, now))));
        int citizenId = citizen.getId();
        String playerName = player.get();
        return request(server, key, transcript, values, lines, MAX_PRIVATE_FACTS, colony, List.of(citizen),
                Fact.privateTalk(playerId), (current, digest) -> {
                    ICitizenData owner = current.getCitizenManager().getCivilian(citizenId);
                    if (owner != null) {
                        bonds.apply(owner, playerId, playerName, digest, open, lines.stream()
                                .filter(line -> !line.speaker().equals(name)).map(MemoryLine::text).toList());
                    }
                });
    }

    private boolean digestColony(MinecraftServer server, int colonyId, Transcript transcript, boolean force) {
        List<MemoryLine> lines = transcript.undigested();
        String key = "colony:" + colonyId;
        if (lines.isEmpty() || !ready(server, key, force)) {
            return false;
        }
        IColony colony = Citizens.colony(colonyId);
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
        values.put("where", "the colony's group chat, where players and colonists talk. Most of it is chatter: write down "
                + "only news, plans, decisions and promises, not who joked, teased or asked what");
        values.put("known", known(participants));
        List<BondsService.Owned> open = bonds.openInColony(colony);
        values.put("rules", Templates.render(prompts.get(PromptLibrary.MEMORY_COLONISTS),
                Map.of("promises", BondsService.numberedInColony(colony, open, now))));
        List<Integer> participantIds = participants.stream().map(ICitizenData::getId).toList();
        return request(server, key, transcript, values, lines, MAX_COLONY_FACTS, colony, participants, Fact.COLONY_CHAT,
                (current, digest) -> {
                    bonds.noteColonists(current, participantIds.stream()
                            .map(id -> current.getCitizenManager().getCivilian(id)).filter(Objects::nonNull).toList(),
                            digest.colonists());
                    bonds.applyColony(server, current, digest, open, lines.stream().collect(Collectors.groupingBy(
                            MemoryLine::speaker, Collectors.mapping(MemoryLine::text, Collectors.toList()))));
                });
    }

    /**
     * Asks the character model to write the lines down, and gives the facts to every colonist in
     * {@code owners}. Returns whether the request started.
     */
    private boolean request(MinecraftServer server, String key, Transcript transcript, Map<String, String> values,
                            List<MemoryLine> lines, int maxFacts, IColony colony, List<ICitizenData> owners,
                            String source, BiConsumer<IColony, Digests.Digest> after) {
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
        client.complete(settings.get().withMinTokens(DIGEST_TOKENS).inBackground(), List.of(ChatMessage.user(prompt))).thenAccept(result -> server.execute(() -> {
            digesting.remove(key);
            if (data != target) {
                return;
            }
            switch (result) {
                case ChatResult.Reply reply -> {
                    IColony now = Citizens.colony(colonyId);
                    if (now == null) {
                        return;
                    }
                    Digests.Digest digest = marked(Digests.parseAll(reply.text(), max));
                    List<Digests.Entry> entries = digest.facts();
                    store(now, ownerIds, entries, source);
                    Config.embeddings().ifPresent(endpoint -> {
                        useModel(endpoint);
                        ownerIds.stream().map(id -> now.getCitizenManager().getCivilian(id)).filter(Objects::nonNull)
                                .forEach(owner -> embedFacts(facts(owner), endpoint, server));
                    });
                    if (after != null) {
                        after.accept(now, digest);
                    }
                    transcript.markDigested(mark);
                    retryAt.remove(key);
                    data.setDirty();
                    ModLog.action("Colonists remembered {} facts from {}", entries.size(),
                            key.startsWith("colony:") ? "the colony chat of " + now.getName() : "a private talk");
                }
                case ChatResult.Failure failure -> {
                    retryAt.put(key, (long) server.getTickCount() + RETRY_TICKS);
                    ModLog.warn("Could not write down a conversation, will retry later: {}",
                            failure.reason());
                }
            }
        }));
        return true;
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
    private void store(IColony colony, List<Integer> ownerIds, List<Digests.Entry> entries, String source) {
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

    /** Asks for the vectors of facts that have none yet, in the background; they are used once they come. */
    private void embedFacts(Collection<Fact> facts, LlmEndpoint endpoint, MinecraftServer server) {
        List<String> missing = facts.stream().map(Fact::text)
                .filter(text -> !vectors.containsKey(text) && !embedding.contains(text)).distinct().toList();
        if (missing.isEmpty()) {
            return;
        }
        embedding.addAll(missing);
        MemoryData target = data;
        String model = endpoint.model();
        embeddings.embed(endpoint, missing.stream().map(PlayerMarks::resolve).toList(), FACTS_WAIT)
                .whenComplete((result, error) -> server.execute(() -> {
                    missing.forEach(embedding::remove);
                    if (error != null) {
                        ModLog.warn("Could not get embeddings for {} facts: {}", missing.size(),
                                error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                        return;
                    }
                    if (data == target && model.equals(vectorModel)) {
                        for (int i = 0; i < missing.size(); i++) {
                            vectors.put(missing.get(i), result.get(i));
                        }
                    }
                }));
    }

    /** Vectors of another model don't compare with the new one's: they are made again. */
    private void useModel(LlmEndpoint endpoint) {
        if (!endpoint.model().equals(vectorModel)) {
            vectors.clear();
            vectorModel = endpoint.model();
        }
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

    /** The digest with players' names in its promise and conduct texts kept as marks, like the facts. */
    private Digests.Digest marked(Digests.Digest digest) {
        Map<UUID, List<String>> names = data.playerNames;
        return new Digests.Digest(digest.facts(),
                digest.promises().stream().map(made -> new Digests.NewPromise(made.days(), made.about(), made.worth(),
                        PlayerMarks.markNames(made.text(), names))).toList(),
                digest.resolutions(),
                digest.conduct().stream().map(note -> new Digests.Conduct(note.kind(),
                        PlayerMarks.markNames(note.note(), names))).toList(),
                digest.colonists(),
                digest.colonyPromises().stream().map(made -> new Digests.ColonyPromise(made.player(), made.colonist(),
                        made.days(), made.about(), made.worth(), PlayerMarks.markNames(made.text(), names))).toList());
    }

    private boolean ready(MinecraftServer server, String key, boolean force) {
        return !digesting.contains(key) && (force || retryAt.getOrDefault(key, Long.MIN_VALUE) <= server.getTickCount());
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

    /** Forgets a deleted colony's chat; its colonists go through {@link #forget}. */
    public void forgetColonyChat(int colonyId) {
        if (data != null && data.colonyChats.remove(colonyId) != null) {
            data.setDirty();
        }
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
