package me.bytefox.livingcolonists.social;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.Dice;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.mood.ColonyMoods;
import me.bytefox.livingcolonists.mood.Conditions;
import me.bytefox.livingcolonists.mood.Mood;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A word in passing (#68), as villagers in Stardew Valley say: walking past a colonist, now and then they say something
 * in a speech bubble over their head, nothing in the chat: about the time of day, the weather, how they are, the
 * colony's mood, or a hello to a friend. Set lines in the colonist's voice, with or without a model. Server thread only.
 */
public final class Barks {
    /** How often players are looked at, in server ticks (two seconds). */
    private static final int CHECK = 40;
    /** How near a colonist must be, in blocks. */
    private static final int NEAR = 6;
    /** A colonist says something in passing at most this often (three minutes), a player hears one at most every 20 s. */
    private static final long COLONIST_GAP = 3 * 60 * 20;
    private static final long PLAYER_GAP = 20 * 20;
    private static final double CHANCE = 0.35;

    /** Shows a line in a speech bubble over the colonist, to the player only. */
    public interface Bubble {
        void show(AbstractEntityCitizen colonist, String text, ServerPlayer player);
    }

    /** Picks a set line of the occasion in the voice, in the locale. */
    public interface Lines {
        Optional<String> pick(String occasion, String voice, String locale, Random random);
    }

    private final BondsService bonds;
    private final ColonyMoods moods;
    private final Function<ICitizenData, String> voice;
    private final Lines lines;
    private final Bubble bubble;
    private final Random random = Dice.random();
    private final Map<String, Long> colonistSaid = new HashMap<>();
    private final Map<UUID, Long> playerHeard = new HashMap<>();

    public Barks(BondsService bonds, ColonyMoods moods, Function<ICitizenData, String> voice, Lines lines, Bubble bubble) {
        this.bonds = bonds;
        this.moods = moods;
        this.voice = voice;
        this.lines = lines;
        this.bubble = bubble;
    }

    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % CHECK != 0 || !Config.BARKS.get() || !Config.SPEECH_BUBBLES.get()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (now - playerHeard.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < PLAYER_GAP) {
                continue;
            }
            IColony colony = Colonies.at(player);
            if (colony == null || random.nextDouble() >= CHANCE) {
                continue;
            }
            nearest(colony, player, now).ifPresent(citizen -> {
                String occasion = occasion(colony, citizen, player, now);
                lines.pick(occasion, voice.apply(citizen), player.clientInformation().language(), random).ifPresent(text -> {
                    bubble.show(citizen.getEntity().get(), text, player);
                    colonistSaid.put(key(citizen), now);
                    playerHeard.put(player.getUUID(), now);
                    Counters.add("barks");
                });
            });
        }
    }

    /** The nearest colonist up and about who hasn't said anything in passing for a while. */
    private Optional<ICitizenData> nearest(IColony colony, ServerPlayer player, long now) {
        ICitizenData best = null;
        double bestDistance = (double) NEAR * NEAR;
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            var entity = citizen.getEntity();
            if (entity.isEmpty() || !entity.get().isAlive() || entity.get().isSleeping()
                    || now - colonistSaid.getOrDefault(key(citizen), Long.MIN_VALUE / 2) < COLONIST_GAP) {
                continue;
            }
            double distance = entity.get().blockPosition().distSqr(player.blockPosition());
            if (distance <= bestDistance) {
                best = citizen;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** What it is about, the most pressing first: their own trouble, the colony's mood, the player, then the day. */
    static String occasion(Mood mood, int rapport, boolean sick, boolean homeless, boolean hungry, boolean rain, long dayTime) {
        if (sick) {
            return "bark.sick";
        }
        if (hungry) {
            return "bark.hungry";
        }
        if (homeless) {
            return "bark.homeless";
        }
        switch (mood) {
            case PANIC -> {
                return "bark.panic";
            }
            case GRIEF -> {
                return "bark.grief";
            }
            case FESTIVE, JOY -> {
                return "bark.festive";
            }
            default -> {
            }
        }
        if (rapport >= 20) {
            return "bark.friend";
        }
        if (rapport <= -20) {
            return "bark.cold";
        }
        if (rain) {
            return "bark.rain";
        }
        long time = Math.floorMod(dayTime, 24000L);
        return time < 3000 ? "bark.morning" : time >= 12000 && time < 13500 ? "bark.evening" : time >= 13500 ? "bark.night" : "bark.day";
    }

    private String occasion(IColony colony, ICitizenData citizen, ServerPlayer player, long now) {
        boolean sick = false;
        try {
            sick = citizen.getCitizenDiseaseHandler() != null && citizen.getCitizenDiseaseHandler().isSick();
        } catch (RuntimeException | LinkageError e) {
            // Unknown is well.
        }
        var world = colony.getWorld();
        return occasion(moods.leading(colony.getID(), now).mood(),
                bonds.playerRapport(citizen, player.getUUID()), sick, citizen.getHomeBuilding() == null && !citizen.isChild(),
                citizen.getSaturation() < Conditions.HUNGRY, world != null && world.isRaining(), world == null ? 6000 : world.getDayTime());
    }

    private static String key(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }

    public void clear() {
        colonistSaid.clear();
        playerHeard.clear();
    }
}
