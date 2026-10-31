package me.bytefox.livingcolonists.events;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** What the mod reacts to, posted on {@link ModEvents#BUS}. */
public final class GameEvents {
    private GameEvents() {
    }

    public record PlayerJoined(ServerPlayer player) {
    }

    public record PlayerLeft(ServerPlayer player) {
    }

    public record PlayerDied(ServerPlayer player) {
    }

    public record PlayerChangedWorld(ServerPlayer player, ResourceKey<Level> to) {
    }

    /** A player earned an advancement; {@code title} is its title in English. */
    public record AdvancementEarned(ServerPlayer player, String id, String title) {
    }

    /** The colony gave a colonist a job or took one; either side null for none. */
    public record JobChanged(ICitizenData citizen, String from, String to) {
    }

    /** A colonist fell ill; {@code illness} as MineColonies names it. */
    public record FellIll(ICitizenData citizen, String illness) {
    }

    /**
     * Something happened in a colony that colonists talk about: a death, a birth, a building, a raid,
     * a milestone. {@code what} is how it is told at once ("The Tavern was just finished.").
     */
    public record ColonyHappened(IColony colony, String what, boolean building, boolean urgent) {
    }
}
