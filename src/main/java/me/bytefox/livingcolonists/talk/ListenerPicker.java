package me.bytefox.livingcolonists.talk;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses which colonist a player is talking to: the first one along the player's line of sight,
 * otherwise the nearest one in range. Works on plain vectors so it can be tested without a world.
 */
public final class ListenerPicker {
    /**
     * A possible listener, with the offset from the player's eyes to the middle of its body.
     */
    public record Candidate<T>(T id, double dx, double dy, double dz) {
        double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private ListenerPicker() {
    }

    /**
     * @param lookX        normalised look direction of the player
     * @param maxDistance  how far a listener may be
     * @param targetRadius how far from the line of sight a body still counts as looked at
     */
    public static <T> Optional<T> pick(List<Candidate<T>> candidates, double lookX, double lookY, double lookZ,
                                       double maxDistance, double targetRadius) {
        Optional<T> lookedAt = lookedAt(candidates, lookX, lookY, lookZ, maxDistance, targetRadius);
        if (lookedAt.isPresent()) {
            return lookedAt;
        }
        return candidates.stream()
                .filter(c -> c.distance() <= maxDistance)
                .min(Comparator.comparingDouble(Candidate::distance))
                .map(Candidate::id);
    }

    /** Only the first candidate along the line of sight, without the nearest-one fallback. */
    public static <T> Optional<T> lookedAt(List<Candidate<T>> candidates, double lookX, double lookY, double lookZ,
                                           double maxDistance, double targetRadius) {
        return candidates.stream()
                .filter(c -> c.distance() <= maxDistance)
                .filter(c -> along(c, lookX, lookY, lookZ) > 0)
                .filter(c -> offAxis(c, lookX, lookY, lookZ) <= targetRadius)
                .min(Comparator.comparingDouble(c -> along(c, lookX, lookY, lookZ)))
                .map(Candidate::id);
    }

    private static double along(Candidate<?> c, double lookX, double lookY, double lookZ) {
        return c.dx() * lookX + c.dy() * lookY + c.dz() * lookZ;
    }

    private static double offAxis(Candidate<?> c, double lookX, double lookY, double lookZ) {
        double t = along(c, lookX, lookY, lookZ);
        double distance = c.distance();
        return Math.sqrt(Math.max(0, distance * distance - t * t));
    }
}
