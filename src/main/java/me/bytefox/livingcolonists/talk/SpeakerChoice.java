package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.bonds.Bonds;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.memory.ThreadPolicy;
import me.bytefox.livingcolonists.personality.Personality;
import me.bytefox.livingcolonists.personality.PersonalityService;

/**
 * Who speaks next in the colony chat and whom a colonist turns to: by talkativeness and character, how they get
 * along, who is near or works alongside, family, and who was just named, as {@link ThreadPolicy} weighs it.
 * Server thread only.
 */
final class SpeakerChoice {
    /** How much likelier a colonist named in the last line, or already talking, is to speak next. */
    private static final double NAMED_BONUS = 6;
    private static final double TALKING_BONUS = 2;
    private static final double FAMILY_BONUS = 1.5;

    private final MemoryService memory;
    private final PersonalityService personalities;
    private final BondsService bonds;
    /** Whether a colonist of a colony keeps quiet for now: a newborn joins only when named. */
    private final BiPredicate<Integer, Integer> hushed;
    private final Random random = me.bytefox.livingcolonists.Dice.random();

    SpeakerChoice(MemoryService memory, PersonalityService personalities, BondsService bonds,
                  BiPredicate<Integer, Integer> hushed) {
        this.memory = memory;
        this.personalities = personalities;
        this.bonds = bonds;
        this.hushed = hushed;
    }

    /**
     * Someone for a colonist to turn to by name: friends and those nearby more likely, rivals and the
     * far away less, nobody ruled out.
     */
    Optional<ICitizenData> pickAddressee(IColony colony, ICitizenData speaker) {
        List<ThreadPolicy.Candidate<Integer>> candidates = new ArrayList<>();
        for (ICitizenData other : colony.getCitizenManager().getCitizens()) {
            if (other.getId() == speaker.getId() || hushed.test(colony.getID(), other.getId())) {
                continue;
            }
            double base = Bonds.replyWeight(bonds.colonists().rapport(colony, other.getId(), speaker.getId()));
            candidates.add(new ThreadPolicy.Candidate<>(other.getId(),
                    ThreadPolicy.joinWeight(base, distance(speaker, other), coworkers(speaker, other), 0)));
        }
        return ThreadPolicy.pickSpeaker(candidates, speaker.getId(), random).map(id -> colony.getCitizenManager().getCivilian(id));
    }

    private static double distance(ICitizenData a, ICitizenData b) {
        if (a == null || b == null || a.getEntity().isEmpty() || b.getEntity().isEmpty()) {
            return -1;
        }
        return Math.sqrt(a.getEntity().get().distanceToSqr(b.getEntity().get()));
    }

    private static boolean coworkers(ICitizenData a, ICitizenData b) {
        return a != null && b != null && a.getWorkBuilding() != null && a.getWorkBuilding().equals(b.getWorkBuilding());
    }

    /**
     * Who speaks next, never the one who just did: talkative archetypes more often, much more so a
     * colonist named in the last line, and those already in the thread or family of the last speaker.
     */
    Optional<Integer> pickSpeaker(IColony colony, ChatThread thread) {
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        List<MemoryLine> lines = memory.colonyChat(colony);
        String lastText = lines.isEmpty() ? "" : lines.get(lines.size() - 1).text();
        OptionalInt named = Addressing.mentioned(lastText, citizens.stream().map(ICitizenData::getName).toList());
        ICitizenData last = colony.getCitizenManager().getCivilian(thread.lastSpeaker);
        Set<Integer> family = family(last);
        List<ThreadPolicy.Candidate<Integer>> candidates = new ArrayList<>();
        for (int i = 0; i < citizens.size(); i++) {
            ICitizenData citizen = citizens.get(i);
            double weight = ThreadPolicy.joinWeight(
                    talkativeness(citizen) * Bonds.replyWeight(bonds.colonists().rapport(colony, citizen.getId(), thread.lastSpeaker)),
                    distance(citizen, last), coworkers(citizen, last), thread.lines.getOrDefault(citizen.getId(), 0));
            if (hushed.test(colony.getID(), citizen.getId()) && !(named.isPresent() && named.getAsInt() == i)) {
                continue;
            }
            if (named.isPresent() && named.getAsInt() == i) {
                weight *= NAMED_BONUS;
            }
            if (thread.participants.contains(citizen.getId())) {
                weight *= TALKING_BONUS;
            }
            if (family.contains(citizen.getId())) {
                weight *= FAMILY_BONUS;
            }
            candidates.add(new ThreadPolicy.Candidate<>(citizen.getId(), weight));
        }
        return ThreadPolicy.pickSpeaker(candidates, thread.lastSpeaker, random);
    }

    double contrariness(ICitizenData citizen) {
        Personality personality = personalities.get(citizen);
        return personality == null ? 0.35 : personalities.archetypes().get(personality.archetype()).contrariness();
    }

    double talkativeness(ICitizenData citizen) {
        Personality personality = personalities.get(citizen);
        double value = personality == null ? 0.5 : personalities.archetypes().get(personality.archetype()).talkativeness();
        return Math.max(0.05, value);
    }

    private static Set<Integer> family(ICitizenData citizen) {
        Set<Integer> family = new HashSet<>();
        if (citizen == null) {
            return family;
        }
        if (citizen.getPartner() != null) {
            family.add(citizen.getPartner().getId());
        }
        if (citizen.getChildren() != null) {
            family.addAll(citizen.getChildren());
        }
        if (citizen.getSiblings() != null) {
            family.addAll(citizen.getSiblings());
        }
        return family;
    }

}
