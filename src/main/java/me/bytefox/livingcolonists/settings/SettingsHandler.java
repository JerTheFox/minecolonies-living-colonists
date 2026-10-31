package me.bytefox.livingcolonists.settings;

import java.util.List;
import java.util.UUID;

import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.network.SettingsRequest;
import me.bytefox.livingcolonists.network.SettingsState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server side of the settings screen: everyone may look, only operators (and the owner of a
 * single-player world) may change settings or run checks with the server's key. Server thread.
 */
public final class SettingsHandler {
    private static final int OPERATOR = 2;

    private final SettingsService service;

    public SettingsHandler(SettingsService service) {
        this.service = service;
    }

    public void handle(ServerPlayer player, SettingsRequest request) {
        boolean canEdit = canEdit(player);
        if (request.action() != SettingsRequest.Action.OPEN && !canEdit) {
            send(player, false, "livingcolonists.settings.not_operator", List.of());
            return;
        }
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        switch (request.action()) {
            case OPEN -> send(player, canEdit, "", List.of());
            case APPLY -> {
                List<String> refused = service.apply(request.changes());
                ModLog.info("{} changed settings: {}", player.getGameProfile().getName(),
                        String.join(", ", request.changes().keySet()));
                send(player, true, refused.isEmpty() ? "livingcolonists.settings.saved"
                        : "Not saved: " + String.join("; ", refused), List.of());
            }
            case TEST_CHAT, TEST_CHARACTER -> service.test(request.action() == SettingsRequest.Action.TEST_CHAT
                    ? ModelRole.CHAT : ModelRole.CHARACTER).thenAccept(text -> server.execute(() -> {
                        ServerPlayer again = server.getPlayerList().getPlayer(id);
                        if (again != null) {
                            send(again, true, text, List.of());
                        }
                    }));
            case MODELS, FALLBACK_MODELS -> service.models(request.action() == SettingsRequest.Action.FALLBACK_MODELS)
                    .thenAccept(list -> server.execute(() -> {
                        ServerPlayer again = server.getPlayerList().getPlayer(id);
                        if (again != null) {
                            send(again, true, list.error() != null ? "Models: " + list.error()
                                    : "livingcolonists.settings.models_loaded", list.models());
                        }
                    }));
        }
    }

    public static boolean canEdit(ServerPlayer player) {
        return player.hasPermissions(OPERATOR) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    private void send(ServerPlayer player, boolean canEdit, String message, List<String> models) {
        PacketDistributor.sendToPlayer(player, new SettingsState(canEdit, service.values(), service.usage(), message, models));
    }
}
