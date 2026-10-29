package me.bytefox.livingcolonists.client.settings;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.ModelList;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.llm.UsageStats;
import me.bytefox.livingcolonists.network.SettingsRequest;
import me.bytefox.livingcolonists.network.SettingsState;
import me.bytefox.livingcolonists.settings.SettingsService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Where the settings screen gets and sends settings: the server's, through packets, while in a
 * world (single-player too: the built-in server answers); the local config file from the main
 * menu, which is also what a single-player world uses. Client thread.
 */
public final class ClientSettings {
    private static SettingsScreen open;
    private static SettingsService local;

    private ClientSettings() {
    }

    static void opened(SettingsScreen screen) {
        open = screen;
    }

    static void closed(SettingsScreen screen) {
        if (open == screen) {
            open = null;
        }
    }

    /** Settings from the server. */
    public static void apply(SettingsState state) {
        if (open != null) {
            open.update(state);
        }
    }

    /** Whether the screen talks to a server; false in the main menu. */
    static boolean remote() {
        return Minecraft.getInstance().getConnection() != null;
    }

    /** Whether the server we're on has the mod; meaningless in the main menu. */
    static boolean serverHasMod() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(SettingsRequest.TYPE);
    }

    static void request(SettingsRequest.Action action, Map<String, String> changes) {
        if (remote()) {
            if (serverHasMod()) {
                PacketDistributor.sendToServer(new SettingsRequest(action, changes));
            }
            return;
        }
        SettingsService service = local();
        switch (action) {
            case OPEN -> apply(state(service, ""));
            case APPLY -> {
                List<String> refused = service.apply(changes);
                apply(state(service, refused.isEmpty() ? "livingcolonists.settings.saved"
                        : "Not saved: " + String.join("; ", refused)));
            }
            case TEST_CHAT, TEST_CHARACTER -> later(service.test(action == SettingsRequest.Action.TEST_CHAT
                    ? ModelRole.CHAT : ModelRole.CHARACTER).thenApply(text -> state(service, text)));
            case MODELS, FALLBACK_MODELS -> later(service.models(action == SettingsRequest.Action.FALLBACK_MODELS)
                    .thenApply(list -> models(service, list)));
        }
    }

    private static SettingsService local() {
        if (local == null) {
            UsageStats stats = new UsageStats();
            local = new SettingsService(new LlmClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build(), stats, LlmClient.DEFAULT_BACKOFF, line -> {
            }), stats);
        }
        return local;
    }

    private static void later(CompletableFuture<SettingsState> result) {
        result.thenAccept(state -> Minecraft.getInstance().execute(() -> apply(state)));
    }

    private static SettingsState state(SettingsService service, String message) {
        return new SettingsState(true, service.values(), List.of(), message, List.of());
    }

    private static SettingsState models(SettingsService service, ModelList list) {
        return new SettingsState(true, service.values(), List.of(),
                list.error() != null ? "Models: " + list.error() : "livingcolonists.settings.models_loaded", list.models());
    }
}
