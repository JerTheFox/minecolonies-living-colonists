package me.bytefox.livingcolonists.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelList;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.llm.UsageStats;

/**
 * Reads and changes the mod's settings for the settings screen: on the server for players in a
 * world, in the client itself from the main menu. API keys never leave: a snapshot only says
 * whether each is set, and a new key is written once and never read back.
 */
public final class SettingsService {
    /** What a SECRET setting shows when a key is set. */
    public static final String SECRET_SET = "set";

    private final LlmClient client;
    private final UsageStats usage;

    public SettingsService(LlmClient client, UsageStats usage) {
        this.client = client;
        this.usage = usage;
    }

    /** Every setting by id, with keys only as {@link #SECRET_SET} or empty. */
    public Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Setting setting : SettingsSchema.ALL) {
            String value = setting.read().get();
            values.put(setting.id(), setting.kind().type() == Setting.Type.SECRET ? (value.isBlank() ? "" : SECRET_SET) : value);
        }
        return values;
    }

    /**
     * Applies the changes that pass the checks and saves the config file; returns what was refused,
     * as "id: reason". A key given as empty text is cleared.
     */
    public List<String> apply(Map<String, String> changes) {
        List<String> refused = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Optional<Setting> setting = SettingsSchema.find(change.getKey());
            if (setting.isEmpty()) {
                refused.add(change.getKey() + ": unknown setting");
                continue;
            }
            SettingValues.Checked checked = SettingValues.check(setting.get().kind(), change.getValue());
            if (checked.isError()) {
                refused.add(change.getKey() + ": " + checked.error());
                continue;
            }
            setting.get().write().accept(checked.value());
            changed = true;
        }
        if (changed) {
            Config.SPEC.save();
        }
        return refused;
    }

    /** Requests and tokens per model, for this run and in total, one line each. */
    public List<String> usage() {
        List<String> lines = new ArrayList<>();
        usage.total().forEach((model, total) -> {
            UsageStats.Counts session = usage.session().getOrDefault(model, UsageStats.Counts.ZERO);
            lines.add(model + ": " + total.requests() + " requests (" + session.requests() + " this run), "
                    + total.failures() + " failed, " + total.promptTokens() + " prompt + " + total.completionTokens()
                    + " reply tokens, " + total.cachedTokens() + " from cache, about " + total.averageLatencyMillis() + " ms");
        });
        return lines;
    }

    /** Sends the model a tiny request; completes with a line to show: the latency and answer, or the error. */
    public CompletableFuture<String> test(ModelRole role) {
        Optional<LlmSettings> settings = Config.llmSettings(role);
        if (settings.isEmpty()) {
            return CompletableFuture.completedFuture("no API key: set apiKey or the environment variable in apiKeyEnv");
        }
        long start = System.nanoTime();
        String model = settings.get().primary().model();
        return client.complete(settings.get().withMinTokens(2000),
                        List.of(ChatMessage.user("Reply with the single word OK.")))
                .thenApply(result -> switch (result) {
                    case ChatResult.Reply reply -> model + " answered in " + (System.nanoTime() - start) / 1_000_000
                            + " ms: " + reply.text();
                    case ChatResult.Failure failure -> model + " failed: " + failure.reason();
                });
    }

    /** The models the main API (or the fallback one) offers. */
    public CompletableFuture<ModelList> models(boolean fallback) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            return CompletableFuture.completedFuture(ModelList.failed("no API key"));
        }
        if (fallback && settings.get().fallback() == null) {
            return CompletableFuture.completedFuture(ModelList.failed("the fallback API is off or has no model"));
        }
        return client.listModels(fallback ? settings.get().fallback() : settings.get().primary(),
                settings.get().timeoutSeconds());
    }
}
