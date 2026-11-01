package me.bytefox.livingcolonists.memory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import net.minecraft.server.MinecraftServer;

/**
 * Colonists' life stories (#51): once a colonist has a few memories, and then once a game week, the character model
 * sums up their life in the colony from the earlier story and the latest facts. One at a time. Server thread only.
 */
final class StoryWriter {
    /** How often a colonist's life story may be started, in server ticks (two minutes); one at a time. */
    private static final int STORY_CHECK = 2 * 60 * 20;
    private boolean writingStory;

    private final MemoryService memory;
    private final LlmClient client;
    private final PromptLibrary prompts;

    StoryWriter(MemoryService memory, LlmClient client, PromptLibrary prompts) {
        this.memory = memory;
        this.client = client;
        this.prompts = prompts;
    }

    /**
     * Writes the life story (#51) of the first colonist whose story is due: enough facts and none yet, or the
     * last a game week old. The character model sums up the earlier story and the facts in a few sentences.
     */
    void tick(MinecraftServer server, MemoryData data) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHARACTER);
        if (server.getTickCount() % STORY_CHECK != 0 || writingStory || settings.isEmpty()
                || FactLimits.of(MemoryService.level()).facts() == 0) {
            return;
        }
        for (String key : List.copyOf(data.facts.keySet())) {
            String[] parts = key.split(":");
            IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
            ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
            if (citizen == null) {
                continue;
            }
            List<Fact> facts = memory.facts(citizen);
            MemoryData.Story old = data.stories.get(key);
            long now = colony.getWorld().getGameTime();
            if (!LifeStories.due(old == null ? -1 : old.written(), now, facts.size())) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            values.put("name", citizen.getName());
            values.put("job", MemoryService.jobOf(citizen));
            values.put("colony", colony.getName());
            values.put("story", old == null ? "(nothing yet)" : old.text());
            values.put("facts", Digests.memories(facts, now));
            String prompt = Templates.render(prompts.get(PromptLibrary.STORY), values);
            MemoryData target = data;
            writingStory = true;
            client.complete(settings.get().withMinTokens(MemoryService.DIGEST_TOKENS).inBackground(), List.of(ChatMessage.user(prompt)))
                    .thenAccept(result -> server.execute(() -> {
                        writingStory = false;
                        if (memory.data() != target || !(result instanceof ChatResult.Reply reply) || reply.text().isBlank()) {
                            return;
                        }
                        data.stories.put(key, new MemoryData.Story(PlayerMarks.markNames(reply.text().strip(),
                                data.playerNames), now));
                        data.setDirty();
                        ModLog.action("Wrote the life story of {}", citizen.getName());
                    }));
            return;
        }
    }

}
