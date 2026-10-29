package me.bytefox.livingcolonists.personality;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.Tuple;
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
 * Gives colonists a character: an archetype picked when they first talk, and a background the
 * character model writes once. How much of it is used follows the personality level:
 * off - nothing, low - archetype only, default - archetype and a short background,
 * high - archetype and a longer background.
 *
 * <p>Server thread only, except the background request, which comes back through
 * {@link MinecraftServer#execute}.</p>
 */
public final class PersonalityService {
    /** Probability that a child takes after one of their parents' archetypes. */
    private static final double INHERIT_CHANCE = 0.3;
    /** Token room for a background, so a reasoning model's long thinking doesn't leave it empty. */
    private static final int BIO_TOKENS = 4000;

    private final PromptLibrary prompts;
    private final ArchetypeLibrary archetypes;
    private final LlmClient client;
    private final Random random = new Random();
    /** Backgrounds being written, as {@link PersonalityData#key}. */
    private final Set<String> writing = new HashSet<>();
    private PersonalityData data;

    public PersonalityService(PromptLibrary prompts, ArchetypeLibrary archetypes, LlmClient client) {
        this.prompts = prompts;
        this.archetypes = archetypes;
        this.client = client;
    }

    public void attach(PersonalityData data) {
        this.data = data;
        writing.clear();
    }

    public void detach() {
        data = null;
        writing.clear();
    }

    public ArchetypeLibrary archetypes() {
        return archetypes;
    }

    /** The colonist's character, assigning an archetype and ordering a background if needed. */
    public Optional<Personality> personality(ICitizenData citizen, MinecraftServer server) {
        Level level = Config.level(Module.PERSONALITY);
        if (data == null || level == Level.OFF) {
            return Optional.empty();
        }
        String key = key(citizen);
        Personality personality = data.get(key);
        if (personality == null) {
            personality = new Personality(pickArchetype(citizen).id(), "", citizen.isChild());
            data.put(key, personality);
        }
        boolean wantsBio = level == Level.DEFAULT || level == Level.HIGH;
        boolean grewUp = personality.child() && !citizen.isChild() && !personality.bio().isEmpty();
        if (wantsBio && (personality.bio().isEmpty() || grewUp)) {
            writeBio(citizen, server, false);
        }
        return Optional.of(personality);
    }

    /** {@code {character}} and {@code {background}} for reply prompts; empty strings when not used. */
    public Map<String, String> promptValues(ICitizenData citizen, MinecraftServer server) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("character", "");
        values.put("background", "");
        personality(citizen, server).ifPresent(personality -> {
            values.put("character", Templates.render(prompts.get(PromptLibrary.CHARACTER),
                    Map.of("archetype", archetypes.get(personality.archetype()).prompt())));
            Level level = Config.level(Module.PERSONALITY);
            if (!personality.bio().isEmpty() && level != Level.LOW) {
                values.put("background", Templates.render(prompts.get(PromptLibrary.BACKGROUND),
                        Map.of("bio", personality.bio())));
            }
        });
        return values;
    }

    /** Sets the archetype, or picks a new one with {@code null}. The background is kept. Null if no world is loaded. */
    public Personality setArchetype(ICitizenData citizen, String archetypeId) {
        if (data == null) {
            return null;
        }
        String id = archetypeId != null ? archetypeId : pickArchetype(citizen).id();
        String key = key(citizen);
        Personality old = data.get(key);
        Personality personality = old == null ? new Personality(id, "", citizen.isChild()) : old.withArchetype(id);
        data.put(key, personality);
        return personality;
    }

    /** Asks the character model for a background, replacing the current one when {@code force}. */
    public boolean writeBio(ICitizenData citizen, MinecraftServer server, boolean force) {
        String key = key(citizen);
        Personality personality = data == null ? null : data.get(key);
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHARACTER);
        if (personality == null || settings.isEmpty() || !writing.add(key)) {
            return false;
        }
        boolean child = citizen.isChild();
        String template;
        if (child) {
            template = PromptLibrary.BIO_CHILD;
        } else if (personality.child() && !personality.bio().isEmpty() && !force) {
            template = PromptLibrary.BIO_GROWNUP;
        } else {
            template = PromptLibrary.BIO;
        }
        String prompt = Templates.render(prompts.get(template), bioValues(citizen, personality, child));
        IColony colony = citizen.getColony();
        int colonyId = colony.getID();
        int citizenId = citizen.getId();
        client.complete(settings.get().withMinTokens(BIO_TOKENS), List.of(ChatMessage.user(prompt)))
                .thenAccept(result -> server.execute(() -> {
                    writing.remove(key);
                    if (data == null) {
                        return;
                    }
                    switch (result) {
                        case ChatResult.Reply reply -> {
                            Personality current = data.get(key);
                            if (current != null) {
                                data.put(key, current.withBio(Prompts.cleanBio(reply.text()), child));
                            }
                        }
                        case ChatResult.Failure failure -> LivingColonists.LOGGER.warn(
                                "Could not write a background for colonist {} of colony {}: {}", citizenId, colonyId,
                                failure.reason());
                    }
                }));
        return true;
    }

    private Map<String, String> bioValues(ICitizenData citizen, Personality personality, boolean child) {
        boolean high = Config.level(Module.PERSONALITY) == Level.HIGH;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", citizen.getName());
        values.put("job", Prompts.jobName(citizen.getJob() == null ? null
                : citizen.getJob().getJobRegistryEntry().getKey().getPath()));
        values.put("archetype", archetypes.get(personality.archetype()).prompt());
        values.put("gender", citizen.isFemale() ? "Female" : "Male");
        values.put("skills", skills(citizen).entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        values.put("length", child ? (high ? "2-3 sentences" : "1-2 sentences") : (high ? "3-5 sentences" : "2-3 sentences"));
        values.put("childhood", personality.bio());
        Tuple<String, String> parents = citizen.getParents();
        String parentNames = parents == null ? "" : List.of(parents.getA(), parents.getB()).stream()
                .filter(name -> name != null && !name.isBlank()).collect(Collectors.joining(" and "));
        values.put("parents", parentNames.isEmpty() ? "" : "Their parents are " + parentNames + ".");
        return values;
    }

    private Archetype pickArchetype(ICitizenData citizen) {
        return ArchetypePicker.pick(archetypes.all(), skills(citizen), parentArchetypes(citizen), INHERIT_CHANCE, random);
    }

    /** Archetypes of the colonist's parents who still live in the colony and have one. */
    private List<String> parentArchetypes(ICitizenData citizen) {
        Tuple<String, String> parents = citizen.getParents();
        List<String> result = new ArrayList<>();
        if (parents == null || data == null) {
            return result;
        }
        for (ICitizenData other : citizen.getColony().getCitizenManager().getCitizens()) {
            if (other.getName().equals(parents.getA()) || other.getName().equals(parents.getB())) {
                Personality personality = data.get(key(other));
                if (personality != null) {
                    result.add(personality.archetype());
                }
            }
        }
        return result;
    }

    public static Map<String, Integer> skills(ICitizenData citizen) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        for (Skill skill : Skill.values()) {
            skills.put(skill.name(), citizen.getCitizenSkillHandler().getLevel(skill));
        }
        return skills;
    }

    public Personality get(ICitizenData citizen) {
        return data == null ? null : data.get(key(citizen));
    }

    private static String key(ICitizenData citizen) {
        return PersonalityData.key(citizen.getColony().getID(), citizen.getId());
    }
}
