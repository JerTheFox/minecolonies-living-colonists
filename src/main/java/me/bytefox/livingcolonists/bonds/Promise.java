/*
 * Based on PromiseStore.Promise from Colonist Errands by Lovkar, licensed GPL-3.0-or-later
 * (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/PromiseStore.java). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.Locale;
import java.util.UUID;

/**
 * A promise a player made to a colonist.
 *
 * @param id       unique within the world, so a promise can be found again after it changed
 * @param maker    the player who promised
 * @param makerName the name colonists know the maker by
 * @param text     the promise, in English, as the character model wrote it down
 * @param made     world game time when it was made
 * @param due      world game time it is due by, or 0 without a deadline
 * @param resolved world game time it was kept, broken or cancelled
 * @param needSeen the need it is about (no home, no job, sick) was seen unmet, so meeting it now counts as keeping it
 * @param reminded the colonist already reminded the maker of it after it became overdue
 * @param requestId the colony request it was made for ("I will work on it!"), as the request token's UUID; empty if none
 */
public record Promise(long id, UUID maker, String makerName, String text, About about, long made, long due,
                      Status status, long resolved, boolean needSeen, boolean reminded, String requestId) {

    public enum Status {
        OPEN, KEPT, BROKEN, CANCELLED
    }

    /** What the promise is about; housing, work and health promises close by themselves when the need is met. */
    public enum About {
        HOUSING, FOOD, HEALTH, WORK, BUILDING, SUPPLY, GENERAL;

        public static About of(String text) {
            for (About about : values()) {
                if (about.name().equalsIgnoreCase(text.strip())) {
                    return about;
                }
            }
            return GENERAL;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public Promise resolve(Status to, long now) {
        return new Promise(id, maker, makerName, text, about, made, due, to, now, needSeen, reminded, requestId);
    }

    public Promise withNeedSeen() {
        return new Promise(id, maker, makerName, text, about, made, due, status, resolved, true, reminded, requestId);
    }

    public Promise withReminded() {
        return new Promise(id, maker, makerName, text, about, made, due, status, resolved, needSeen, true, requestId);
    }
}
