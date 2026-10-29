package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.talk.Prompts;

/**
 * Read-only functions a colonist's model can call to look up colony data instead of guessing:
 * facts about another colonist, who fits a profession best, and an overview of the colony.
 * Run on the server thread; each returns a small JSON object.
 */
public final class ColonyTools {
    private static final int MAX_NAMES = 10;
    private static final int MAX_RANKED = 5;
    private static final int MAX_BUILDINGS = 30;
    /** Words players and models use for professions, to MineColonies job registry paths. */
    private static final Map<String, String> PROFESSION_ALIASES = Map.of(
            "courier", "deliveryman", "forester", "lumberjack", "woodcutter", "lumberjack",
            "smith", "blacksmith", "guard", "knight", "fisher", "fisherman", "warehouse_worker", "deliveryman");

    private final PerceptionService perception;

    public ColonyTools(PerceptionService perception) {
        this.perception = perception;
    }

    public List<ToolSpec> specs() {
        return List.of(
                spec("colonist_info", "Facts about one colonist of your colony: profession, age, skills, home, needs, "
                        + "family. Write the name as the colony spells it, in Latin letters.",
                        "name", "The colonist's name"),
                spec("best_for_job", "Colonists ranked by how well their skills fit a profession (its primary and "
                        + "secondary skill), with their current jobs.",
                        "profession", "Profession in English as MineColonies names it, e.g. builder, farmer, miner, "
                                + "lumberjack, fisherman, cook, knight, deliveryman"),
                spec("colony_overview", "Overview of your colony: population, who has no job or home, who is sick, "
                        + "hunger, a raid, and the buildings with their levels.", null, null));
    }

    private static ToolSpec spec(String name, String description, String parameter, String parameterDescription) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        if (parameter != null) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "string");
            property.addProperty("description", parameterDescription);
            properties.add(parameter, property);
            required.add(parameter);
        }
        schema.add("properties", properties);
        schema.add("required", required);
        return new ToolSpec(name, description, schema);
    }

    /** Runs one call for {@code asker}, whose colony it looks into. Never throws. */
    public String run(ToolCall call, ICitizenData asker) {
        try {
            JsonObject arguments = call.arguments().isBlank() ? new JsonObject()
                    : JsonParser.parseString(call.arguments()).getAsJsonObject();
            IColony colony = asker.getColony();
            JsonObject result = switch (call.name()) {
                case "colonist_info" -> colonistInfo(colony, string(arguments, "name"));
                case "best_for_job" -> bestForJob(colony, string(arguments, "profession"));
                case "colony_overview" -> overview(colony);
                default -> error("There is no function " + call.name() + ".");
            };
            return result.toString();
        } catch (RuntimeException e) {
            LivingColonists.LOGGER.debug("Colony function {} failed: {}", call.name(), e.toString());
            return error("The colony records couldn't be read right now.").toString();
        }
    }

    private JsonObject colonistInfo(IColony colony, String name) {
        Optional<ICitizenData> found = find(colony, name);
        if (found.isEmpty()) {
            JsonObject error = error("No colonist called " + name + ".");
            error.add("colonists", names(colony.getCitizenManager().getCitizens()));
            return error;
        }
        ICitizenData citizen = found.get();
        ColonistFacts facts = perception.facts(citizen);
        JsonObject info = new JsonObject();
        info.addProperty("name", citizen.getName());
        info.addProperty("profession", facts.job() == null ? "unemployed" : facts.job());
        info.addProperty("age", citizen.isChild() ? "child" : "adult");
        JsonObject skills = new JsonObject();
        for (Skill skill : Skill.values()) {
            skills.addProperty(skill.name(), citizen.getCitizenSkillHandler().getLevel(skill));
        }
        info.add("skills", skills);
        info.addProperty("home", facts.homeLevel() == 0 ? "none" : "level " + facts.homeLevel() + " house");
        info.addProperty("hungry", facts.saturation() < 8);
        if (facts.sickness() != null) {
            info.addProperty("sick", facts.sickness());
        }
        if (facts.happiness() >= 0) {
            info.addProperty("happiness", Math.round(facts.happiness() * 10) / 10.0 + " of 10");
        }
        JsonArray family = new JsonArray();
        for (ColonistFacts.Relative relative : facts.family()) {
            family.add(relative.relation() + ": " + relative.name());
        }
        info.add("family", family);
        return info;
    }

    private static JsonObject bestForJob(IColony colony, String profession) {
        String wanted = profession.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        String path = PROFESSION_ALIASES.getOrDefault(wanted, wanted);
        WorkerBuildingModule module = null;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            for (WorkerBuildingModule worker : building.getModules(WorkerBuildingModule.class)) {
                if (worker.getJobEntry() != null && worker.getJobEntry().getKey().getPath().equals(path)) {
                    module = worker;
                    break;
                }
            }
            if (module != null) {
                break;
            }
        }
        if (module == null) {
            return error("The colony has no workplace for " + profession + " yet, so nobody can work as one, and its "
                    + "skills can't be looked up.");
        }
        Skill primary = module.getPrimarySkill();
        Skill secondary = module.getSecondarySkill();
        List<ICitizenData> adults = new ArrayList<>(colony.getCitizenManager().getCitizens().stream()
                .filter(c -> !c.isChild()).toList());
        adults.sort(Comparator.comparingInt((ICitizenData c) -> 2 * c.getCitizenSkillHandler().getLevel(primary)
                + c.getCitizenSkillHandler().getLevel(secondary)).reversed());
        JsonObject result = new JsonObject();
        result.addProperty("profession", path);
        result.addProperty("primarySkill", primary.name());
        result.addProperty("secondarySkill", secondary.name());
        JsonArray ranking = new JsonArray();
        for (ICitizenData citizen : adults.stream().limit(MAX_RANKED).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", citizen.getName());
            entry.addProperty(primary.name(), citizen.getCitizenSkillHandler().getLevel(primary));
            entry.addProperty(secondary.name(), citizen.getCitizenSkillHandler().getLevel(secondary));
            entry.addProperty("currentJob", citizen.getJob() == null ? "unemployed"
                    : Prompts.jobName(citizen.getJob().getJobRegistryEntry().getKey().getPath()));
            ranking.add(entry);
        }
        result.add("ranking", ranking);
        return result;
    }

    private static JsonObject overview(IColony colony) {
        List<ICitizenData> all = colony.getCitizenManager().getCitizens();
        JsonObject result = new JsonObject();
        result.addProperty("name", colony.getName());
        result.addProperty("colonists", all.size());
        result.addProperty("children", all.stream().filter(ICitizenData::isChild).count());
        result.add("withoutJob", names(all.stream().filter(c -> !c.isChild() && c.getJob() == null).toList()));
        result.add("withoutHome", names(all.stream().filter(c -> c.getHomeBuilding() == null).toList()));
        result.add("sick", names(all.stream().filter(c -> c.getCitizenDiseaseHandler().isSick()).toList()));
        result.addProperty("hungry", all.stream().filter(c -> c.getSaturation() < 8).count());
        result.addProperty("underRaid", colony.getRaiderManager().isRaided());
        JsonArray buildings = new JsonArray();
        colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(b -> b.getBuildingType() != null)
                .limit(MAX_BUILDINGS)
                .forEach(b -> buildings.add(b.getBuildingType().getRegistryName().getPath()
                        + (b.getBuildingLevel() > 0 ? " level " + b.getBuildingLevel() : " (not built yet)")));
        result.add("buildings", buildings);
        return result;
    }

    /** By full name, then first name, then any part of it; case doesn't matter. */
    private static Optional<ICitizenData> find(IColony colony, String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        return citizens.stream().filter(c -> c.getName().equalsIgnoreCase(wanted)).findFirst()
                .or(() -> citizens.stream().filter(c -> c.getName().toLowerCase(Locale.ROOT).split(" ")[0].equals(wanted)).findFirst())
                .or(() -> citizens.stream().filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(wanted)).findFirst());
    }

    private static JsonArray names(List<ICitizenData> citizens) {
        JsonArray names = new JsonArray();
        citizens.stream().limit(MAX_NAMES).forEach(c -> names.add(c.getName()));
        return names;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static JsonObject error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return error;
    }
}
