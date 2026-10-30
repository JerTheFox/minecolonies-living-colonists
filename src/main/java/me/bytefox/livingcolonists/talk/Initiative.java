package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.errands.Notes;
import me.bytefox.livingcolonists.memory.Fact;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.ColonistFacts;
import me.bytefox.livingcolonists.perception.PerceptionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Colonists writing to a player first, unprompted (#4): only while the player is in the colony and
 * not in a conversation; any colonist of it may, as in a group chat. What about and when is the code's dice
 * (see {@link InitiativePolicy}); the line itself comes from the model, in the colonist's voice,
 * mood and the same rules as every reply.
 */
final class Initiative {
    /** Checked every five seconds whether a player's next moment has come. */
    private static final int CHECK_TICKS = 5 * 20;
    /** Something a player told the colonist is worth asking about while it's this fresh, in game ticks. */
    private static final long FRESH_FACT = 3 * 24000L;
    private static final int MIN_IMPORTANCE = 3;

    private final TalkService talk;
    private final ColonyThreads threads;
    private final PerceptionService perception;
    private final MemoryService memory;
    private final BondsService bonds;
    private final Random random = new Random();
    private final Map<UUID, InitiativePolicy.Budget> budgets = new HashMap<>();
    /** When each player's next unprompted line may come, in server ticks; drawn anew after each. */
    private final Map<UUID, Long> nextAt = new HashMap<>();
    /** When each colonist last wrote first to each player: {@code colonyId:citizenId:player}, server ticks. */
    private final Map<String, Long> lastByColonist = new HashMap<>();
    /** When each colonist last wrote first to anyone, and last complained: {@code colonyId:citizenId}, server ticks. */
    private final Map<String, Long> lastSpoke = new HashMap<>();
    private final Map<String, Long> lastComplained = new HashMap<>();
    private InitiativeData data;

    Initiative(TalkService talk, ColonyThreads threads, PerceptionService perception, MemoryService memory,
               BondsService bonds) {
        this.talk = talk;
        this.threads = threads;
        this.perception = perception;
        this.memory = memory;
        this.bonds = bonds;
    }

    void attach(InitiativeData data) {
        this.data = data;
    }

    void detach() {
        data = null;
    }

    void forget(Predicate<String> citizenKey) {
        if (data != null) {
            data.forget(citizenKey);
        }
        lastByColonist.keySet().removeIf(key -> citizenKey.test(key.substring(0, key.lastIndexOf(':'))));
        lastSpoke.keySet().removeIf(citizenKey);
        lastComplained.keySet().removeIf(citizenKey);
    }

    void clear() {
        budgets.clear();
        nextAt.clear();
        lastSpoke.clear();
        lastComplained.clear();
        lastByColonist.clear();
    }

    void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (data == null || now % CHECK_TICKS != 0) {
            return;
        }
        Level level = Config.level(Module.INITIATIVE);
        if (level == Level.OFF) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            // A player new to us gets a pause first too: nobody is greeted the second they log in.
            long due = nextAt.computeIfAbsent(id, key -> now + InitiativePolicy.gap(level, random));
            if (now < due) {
                continue;
            }
            if (!talk.canWriteFirst()) {
                nextAt.put(id, now + InitiativePolicy.gap(level, random));
                continue;
            }
            IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
            InitiativePolicy.Budget budget = budgets.computeIfAbsent(id, key -> new InitiativePolicy.Budget());
            if (colony == null || !TalkService.isInside(player, colony)
                    || !ColonyAccess.canTalk(TalkService.role(player, colony), Config.MEMBERS_ONLY.get())
                    || now - talk.lastMessage(id) < InitiativePolicy.QUIET
                    || threads.isBusy(colony.getID(), now) || talk.errands().hasPending(player, now)
                    || colony.getRaiderManager().isRaided() || !budget.allows(now, level)) {
                nextAt.put(id, now + InitiativePolicy.retry(random));
                continue;
            }
            if (speakUp(player, colony, level, now).isPresent()) {
                budget.spend(now);
                nextAt.put(id, now + InitiativePolicy.gap(level, random));
            } else {
                nextAt.put(id, now + InitiativePolicy.retry(random));
            }
        }
    }

    /** Picks any colonist of the colony and something for them to say; the topic, if a line went out. */
    private Optional<InitiativePolicy.Topic> speakUp(ServerPlayer player, IColony colony, Level level, long now) {
        List<ICitizenData> able = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            var entity = citizen.getEntity();
            if (citizen.isAsleep() || entity.isPresent() && (!entity.get().isAlive() || entity.get().isSleeping())
                    || threads.isHushed(colony.getID(), citizen.getId()) || talk.errands().isWalking(citizen)
                    || now - lastByColonist.getOrDefault(colonistKey(citizen, player), Long.MIN_VALUE / 2)
                    < InitiativePolicy.perColonist(level)) {
                continue;
            }
            Long spoke = lastSpoke.get(citizenKey(citizen));
            double weight = InitiativePolicy.speakerWeight(threads.talkativeness(citizen), spoke == null ? -1 : now - spoke,
                    bonds.playerRapport(citizen, player.getUUID()));
            able.add(citizen);
            weights.add(weight);
            total += weight;
        }
        if (able.isEmpty() || total <= 0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        ICitizenData speaker = able.get(able.size() - 1);
        for (int i = 0; i < able.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                speaker = able.get(i);
                break;
            }
        }
        int rapport = bonds.playerRapport(speaker, player.getUUID());
        boolean complainedLately = now - lastComplained.getOrDefault(citizenKey(speaker), Long.MIN_VALUE / 2)
                < InitiativePolicy.COMPLAINED_LATELY;
        Optional<InitiativePolicy.Topic> topic = InitiativePolicy.pick(topics(speaker, player, now), rapport,
                complainedLately, random);
        if (topic.isEmpty()) {
            return Optional.empty();
        }
        String purpose = purpose(topic.get(), longApart(speaker, player)) + " Keep it short, one or two lines, and don't repeat anything you "
                + "already said to them. If they don't seem to want to talk, that's fine.";
        if (talk.writeFirst(player, speaker, purpose, true, null) != Notes.Delivery.SENT) {
            return Optional.empty();
        }
        note(speaker, player, topic.get(), now);
        LivingColonists.LOGGER.info("{} writes first to {}: {}", speaker.getName(), player.getGameProfile().getName(),
                topic.get().key());
        return topic;
    }

    /** What the colonist could bring up to this player now, without repeating themselves. */
    private List<InitiativePolicy.Topic> topics(ICitizenData citizen, ServerPlayer player, long now) {
        List<InitiativePolicy.Topic> topics = new ArrayList<>();
        long gameTime = citizen.getColony().getWorld().getGameTime();
        String prefix = colonistKey(citizen, player) + ":";
        Map<String, String> issues = issues(citizen);
        // A problem gone (a home found, the cure given) starts from scratch if it ever comes back.
        if (data.complaints.keySet().removeIf(key -> key.startsWith(prefix) && !issues.containsKey(key.substring(prefix.length())))) {
            data.setDirty();
        }
        issues.forEach((key, text) -> {
            InitiativeData.Raised raised = data.complaints.get(prefix + key);
            int times = raised == null ? 0 : raised.times();
            boolean waited = raised == null || gameTime - raised.last() >= InitiativePolicy.COMPLAINT_GAP;
            if (times <= InitiativePolicy.GIVES_UP_AFTER && waited) {
                topics.add(new InitiativePolicy.Topic(InitiativePolicy.Kind.COMPLAINT, key, text, times));
            }
        });
        String playerName = TalkService.playerName(player);
        for (Fact fact : memory.facts(citizen)) {
            String key = "fact:" + Integer.toHexString(fact.text().hashCode());
            if (fact.source().equals(Fact.privateTalk(playerName)) && fact.importance() >= MIN_IMPORTANCE
                    && gameTime - fact.created() <= FRESH_FACT && fresh(prefix + key, gameTime)) {
                topics.add(new InitiativePolicy.Topic(InitiativePolicy.Kind.FOLLOW_UP, key, fact.text(), 0));
            }
        }
        memory.rumor(citizen, playerName, random).ifPresent(fact -> {
            String key = "news:" + Integer.toHexString(fact.text().hashCode());
            if (fresh(prefix + key, gameTime)) {
                topics.add(new InitiativePolicy.Topic(InitiativePolicy.Kind.NEWS, key, fact.text(), 0));
            }
        });
        List<String> fresh = Prompts.TOPICS.stream().filter(t -> fresh(prefix + "topic:" + t, gameTime)).toList();
        if (!fresh.isEmpty()) {
            String chosen = fresh.get(random.nextInt(fresh.size()));
            topics.add(new InitiativePolicy.Topic(InitiativePolicy.Kind.SMALL_TALK, "topic:" + chosen, chosen, 0));
        }
        return topics;
    }

    private boolean fresh(String key, long gameTime) {
        Long last = data.done.get(key);
        return last == null || gameTime - last >= InitiativePolicy.SAME_TOPIC;
    }

    /** The colonist's real problems right now, from what they perceive: key to how to say it. */
    private Map<String, String> issues(ICitizenData citizen) {
        Map<String, String> issues = new HashMap<>();
        ColonistFacts facts = perception.facts(citizen);
        if (!facts.playerMustBring().isEmpty()) {
            issues.put("need:bring", "your work needs " + String.join(", ", facts.playerMustBring())
                    + ", and nobody in the colony can make it, so someone has to bring it");
        }
        if (citizen.getHomeBuilding() == null && !citizen.isChild()) {
            issues.put("need:home", "you have no home to sleep in");
        }
        if (facts.saturation() >= 0 && facts.saturation() < 6 && facts.food() == ColonistFacts.Food.NONE) {
            issues.put("need:food", "you are hungry and there is nothing to eat");
        }
        if (facts.sickness() != null && !facts.beingTreated()) {
            issues.put("need:sick", "you are ill with " + facts.sickness() + " and nobody treats you");
        }
        if (facts.construction() != null && !facts.missingMaterials().isEmpty()) {
            issues.put("need:materials", "your building job (" + facts.construction() + ") still lacks "
                    + String.join(", ", facts.missingMaterials()));
        }
        if (facts.happiness() >= 0 && facts.happiness() < 5 && !facts.unhappyAbout().isEmpty()) {
            issues.put("need:mood", "you are unhappy about " + String.join(", ", facts.unhappyAbout()));
        }
        return issues;
    }

    /** Whether the colonist and the player haven't talked privately for a game day or more. */
    private boolean longApart(ICitizenData citizen, ServerPlayer player) {
        List<MemoryLine> lines = memory.privateTalk(citizen, player.getUUID());
        return lines.isEmpty() || citizen.getColony().getWorld().getGameTime() - lines.get(lines.size() - 1).gameTime() > 24000L;
    }

    private static String purpose(InitiativePolicy.Topic topic, boolean longApart) {
        return switch (topic.kind()) {
            case COMPLAINT -> InitiativePolicy.complaint(topic.text(), topic.raised());
            case FOLLOW_UP -> "to ask, briefly, how something they told you turned out: " + topic.text() + ".";
            case NEWS -> "to pass on a bit of news you heard, as hearsay: " + topic.text() + ".";
            case SMALL_TALK -> "for no particular reason, to chat a little about " + topic.text() + "."
                    + (longApart ? " You haven't talked with them for a while." : "");
        };
    }

    private void note(ICitizenData citizen, ServerPlayer player, InitiativePolicy.Topic topic, long now) {
        long gameTime = citizen.getColony().getWorld().getGameTime();
        String prefix = colonistKey(citizen, player) + ":";
        lastByColonist.put(colonistKey(citizen, player), now);
        lastSpoke.put(citizenKey(citizen), now);
        if (topic.kind() == InitiativePolicy.Kind.COMPLAINT) {
            lastComplained.put(citizenKey(citizen), now);
            data.complaints.put(prefix + topic.key(), new InitiativeData.Raised(topic.raised() + 1, gameTime));
        } else {
            data.done.put(prefix + topic.key(), gameTime);
        }
        data.setDirty();
    }

    private static String citizenKey(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }

    private static String colonistKey(ICitizenData citizen, ServerPlayer player) {
        return citizen.getColony().getID() + ":" + citizen.getId() + ":" + player.getUUID();
    }
}
