/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/tools/ResearchStatusAction.java. Changed: the answer is JSON, stored work
 * credit is left out.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.research.IGlobalResearch;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.IResearchEffect;
import com.minecolonies.api.research.ILocalResearch;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingUniversity;
import com.minecolonies.core.colony.jobs.JobResearch;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.network.chat.MutableComponent;

/** The university, its researchers, and the research going on with what it unlocks. */
public final class ResearchStatus implements Errand {
    /** Research time of the first tier; each tier deeper takes twice as long. */
    private static final double FIRST_TIER = 72.0;
    private static final int MAX_EFFECTS = 3;

    @Override
    public String name() {
        return "research_status";
    }

    @Override
    public ToolSpec spec() {
        return Errands.spec(name(), "The colony's research: the university, its researchers, and what is being "
                + "researched now, how far along and what it unlocks.", null, null);
    }

    @Override
    public boolean isOrder() {
        return false;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        IColony colony = context.colony();
        int level = 0;
        int researchers = 0;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (building instanceof BuildingUniversity university) {
                level = Math.max(level, university.getBuildingLevel());
                for (ICitizenData citizen : university.getAllAssignedCitizen()) {
                    if (citizen.getJob() instanceof JobResearch) {
                        researchers++;
                    }
                }
            }
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("university_level", level);
        reply.addProperty("researchers", researchers);
        JsonArray running = new JsonArray();
        for (ILocalResearch research : colony.getResearchManager().getResearchTree().getResearchInProgress()) {
            IGlobalResearch global = IGlobalResearchTree.getInstance().getResearch(research.getBranch(), research.getId());
            JsonObject entry = new JsonObject();
            entry.addProperty("name", global == null ? research.getId().getPath()
                    : MutableComponent.create(global.getName()).getString());
            double total = FIRST_TIER * Math.pow(2, Math.max(0, research.getDepth() - 1));
            entry.addProperty("percent_done", (int) Math.min(100, Math.round(research.getProgress() * 100.0 / total)));
            if (global != null) {
                List<String> unlocks = new ArrayList<>();
                for (IResearchEffect effect : global.getEffects()) {
                    String text = MutableComponent.create(effect.getName()).getString();
                    if (!text.isBlank() && !text.contains("com.minecolonies") && !unlocks.contains(text)) {
                        unlocks.add(text);
                    }
                    if (unlocks.size() >= MAX_EFFECTS) {
                        break;
                    }
                }
                entry.addProperty("unlocks", String.join("; ", unlocks));
            }
            running.add(entry);
        }
        reply.add("in_progress", running);
        if (level == 0) {
            reply.addProperty("info", "The colony has no university yet, so no research can run.");
        }
        return new Result.Answer(reply);
    }
}
