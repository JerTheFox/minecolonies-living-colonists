package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.bonds.Deadlines;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.bonds.Bonds;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Promise;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import net.minecraft.server.MinecraftServer;

/**
 * Writes down conversations once they go quiet: the character model turns a private talk or the colony chat into
 * facts for the colonists who took part, and into promises and how people behaved for {@link BondsService}. A few
 * at a time, retried later after a failure. Server thread only.
 */
final class Digester {
    /** A conversation counts as over after this long without a line (one minute), and is written down. */
    static final long QUIET_TICKS = 1200;
    /** The colony chat is written down earlier when this many lines wait, before old ones drop off. */
    static final int COLONY_BACKLOG = 12;
    /** Conversations written down at most per check, so a backlog doesn't burst into requests. */
    static final int MAX_DIGESTS_PER_CHECK = 4;
    /** After a failed request, wait this long before trying the same conversation again (five minutes). */
    static final long RETRY_TICKS = 6000;
    /** Facts shown to the model as already known, so it doesn't write them again. */
    static final int KNOWN_IN_DIGEST = 20;
    /** Already noted lines shown before the new ones, for context. */
    static final int CONTEXT_LINES = 4;
    static final int MAX_PRIVATE_FACTS = 5;
    static final int MAX_COLONY_FACTS = 4;

    private final MemoryService memory;
    private final LlmClient client;
    private final PromptLibrary prompts;
    private final BondsService bonds;
    /** Conversations being written down, by transcript key. */
    private final Set<String> digesting = new HashSet<>();
    /** Server tick before which a conversation is not tried again, by transcript key. */
    private final Map<String, Long> retryAt = new HashMap<>();

    Digester(MemoryService memory, LlmClient client, PromptLibrary prompts, BondsService bonds) {
        this.memory = memory;
        this.client = client;
        this.prompts = prompts;
        this.bonds = bonds;
    }

    void clear() {
        digesting.clear();
        retryAt.clear();
    }

    /**
     * Writes down conversations with lines not yet noted: those that went quiet, or all of them with
     * {@code force}. Returns how many requests were started.
     */
    int digest(MinecraftServer server, boolean force) {
        if (memory.data() == null || FactLimits.of(MemoryService.level()).facts() == 0 || Config.llmSettings(ModelRole.CHARACTER).isEmpty()) {
            return 0;
        }
        int started = 0;
        int budget = force ? Integer.MAX_VALUE : MAX_DIGESTS_PER_CHECK;
        for (Map.Entry<String, Transcript> entry : List.copyOf(memory.data().privateTalks.entrySet())) {
            if (started >= budget) {
                return started;
            }
            if (digestPrivate(server, entry.getKey(), entry.getValue(), force)) {
                started++;
            }
        }
        for (Map.Entry<Integer, Transcript> entry : List.copyOf(memory.data().colonyChats.entrySet())) {
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
        long now = colony.getWorld().getGameTime();
        boolean full = lines.size() >= Math.max(2, MemoryLimits.of(MemoryService.level()).lines() - 1);
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
        values.put("who", name + " (" + MemoryService.jobOf(citizen) + ")");
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
                                .filter(line -> !line.speaker().equals(name))
                                .map(line -> new Deadlines.Said(line.text(), line.gameTime())).toList());
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
        long now = colony.getWorld().getGameTime();
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
                .map(citizen -> citizen.getName() + ", " + MemoryService.jobOf(citizen)).collect(Collectors.joining("; ")) + ")");
        values.put("where", "the colony's group chat, where players and colonists talk. Most of it is chatter: write down "
                + "only news, plans, decisions and promises, not who joked, teased or asked what");
        values.put("known", known(participants));
        List<BondsService.Owned> open = bonds.openInColony(colony);
        values.put("rules", Templates.render(prompts.get(PromptLibrary.MEMORY_COLONISTS),
                Map.of("promises", BondsService.numberedInColony(colony, open, now))));
        List<Integer> participantIds = participants.stream().map(ICitizenData::getId).toList();
        return request(server, key, transcript, values, lines, MAX_COLONY_FACTS, colony, participants, Fact.COLONY_CHAT,
                (current, digest) -> {
                    bonds.colonists().noteColonists(current, participantIds.stream()
                            .map(id -> current.getCitizenManager().getCivilian(id)).filter(Objects::nonNull).toList(),
                            digest.colonists());
                    bonds.applyColony(server, current, digest, open, lines.stream().collect(Collectors.groupingBy(
                            MemoryLine::speaker, Collectors.mapping(line -> new Deadlines.Said(line.text(), line.gameTime()),
                                    Collectors.toList()))));
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
        FactLimits limits = FactLimits.of(MemoryService.level());
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
        MemoryData target = memory.data();
        digesting.add(key);
        client.complete(settings.get().withMinTokens(MemoryService.DIGEST_TOKENS).inBackground(), List.of(ChatMessage.user(prompt))).thenAccept(result -> server.execute(() -> {
            digesting.remove(key);
            if (memory.data() != target) {
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
                    memory.store(now, ownerIds, entries, source);
                    Config.embeddings().ifPresent(endpoint -> {
                        memory.vectors().useModel(endpoint);
                        ownerIds.stream().map(id -> now.getCitizenManager().getCivilian(id)).filter(Objects::nonNull)
                                .forEach(owner -> memory.vectors().embed(memory.facts(owner), endpoint, server));
                    });
                    if (after != null) {
                        after.accept(now, digest);
                    }
                    transcript.markDigested(mark);
                    retryAt.remove(key);
                    target.setDirty();
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

    /** The digest with players' names in its promise and conduct texts kept as marks, like the facts. */
    private Digests.Digest marked(Digests.Digest digest) {
        Map<UUID, List<String>> names = memory.data().playerNames;
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

    private String known(List<ICitizenData> citizens) {
        Set<String> facts = new LinkedHashSet<>();
        for (ICitizenData citizen : citizens) {
            memory.facts(citizen).forEach(fact -> facts.add(fact.text()));
        }
        List<String> recent = new ArrayList<>(facts);
        recent = recent.subList(Math.max(0, recent.size() - KNOWN_IN_DIGEST), recent.size());
        return recent.isEmpty() ? "(nothing yet)" : recent.stream().map(fact -> "- " + fact).collect(Collectors.joining("\n"));
    }

    private static String text(List<MemoryLine> lines, String empty) {
        return lines.isEmpty() ? empty
                : lines.stream().map(line -> "<" + line.speaker() + "> " + line.text()).collect(Collectors.joining("\n"));
    }

}
