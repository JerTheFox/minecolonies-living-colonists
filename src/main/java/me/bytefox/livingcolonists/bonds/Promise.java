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
 * @param worth    1 (a trifle) to 5 (something precious); 0 for ordinary, when nobody said
 * @param requestId the colony request it was made for ("I will work on it!"), as the request token's UUID; empty if none
 * @param confirm  whether the player said it was a promise: one the character model found in talk is asked about
 */
public record Promise(long id, UUID maker, String makerName, String text, int worth, About about, long made, long due,
                      Status status, long resolved, boolean needSeen, boolean reminded, String requestId, Confirm confirm,
                      String shown) {

    /** One without its text in the player's language: shown in English, as it is kept. */
    public Promise(long id, UUID maker, String makerName, String text, int worth, About about, long made, long due,
                   Status status, long resolved, boolean needSeen, boolean reminded, String requestId, Confirm confirm) {
        this(id, maker, makerName, text, worth, about, made, due, status, resolved, needSeen, reminded, requestId, confirm, "");
    }

    /** A promise with nothing to confirm: made in one of MineColonies' windows, or from before promises were asked about. */
    public Promise(long id, UUID maker, String makerName, String text, int worth, About about, long made, long due,
                   Status status, long resolved, boolean needSeen, boolean reminded, String requestId) {
        this(id, maker, makerName, text, worth, about, made, due, status, resolved, needSeen, reminded, requestId,
                Confirm.CONFIRMED);
    }

    /**
     * Whether the player stands by it. The character model sometimes takes "I'll have a look" for a promise, so one it
     * found in talk is asked about first; one nobody answered for stays vague: no reminders, no grudge when it lapses.
     */
    public enum Confirm {
        CONFIRMED, ASKED, VAGUE
    }

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
        return new Promise(id, maker, makerName, text, worth, about, made, due, to, now, needSeen, reminded, requestId, confirm, shown);
    }

    public Promise withNeedSeen() {
        return new Promise(id, maker, makerName, text, worth, about, made, due, status, resolved, true, reminded, requestId, confirm, shown);
    }

    public Promise withReminded() {
        return new Promise(id, maker, makerName, text, worth, about, made, due, status, resolved, needSeen, true, requestId, confirm, shown);
    }

    /** The same promise, now waiting for the player to say whether they meant it. */
    public Promise asked() {
        return withConfirm(Confirm.ASKED);
    }

    public Promise withConfirm(Confirm to) {
        return new Promise(id, maker, makerName, text, worth, about, made, due, status, resolved, needSeen, reminded, requestId, to, shown);
    }

    /** The same promise with its text as the player reads it: a short task in their language. */
    public Promise withShown(String to) {
        return new Promise(id, maker, makerName, text, worth, about, made, due, status, resolved, needSeen, reminded, requestId,
                confirm, to == null ? "" : to);
    }

    /**
     * What the player reads: the short task in their language when there is one (a translation key and its arguments
     * after {@link #KEY}, for promises from MineColonies' windows), else the promise as kept.
     */
    public net.minecraft.network.chat.Component display() {
        if (shown.startsWith(KEY)) {
            String[] parts = shown.substring(KEY.length()).split("\t");
            Object[] args = new Object[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                args[i - 1] = parts[i].startsWith(KEY) ? net.minecraft.network.chat.Component.translatable(parts[i].substring(KEY.length()))
                        : parts[i];
            }
            return net.minecraft.network.chat.Component.translatable(parts[0], args);
        }
        return net.minecraft.network.chat.Component.literal(shown.isEmpty()
                ? me.bytefox.livingcolonists.memory.PlayerMarks.resolve(text) : shown);
    }

    /** Marks a translation key in {@link #shown}; arguments are tab-separated, a key among them marked the same. */
    public static final String KEY = "@";

    /** A shown text from a translation key and its arguments. */
    public static String translated(String key, String... args) {
        return KEY + key + (args.length == 0 ? "" : "\t" + String.join("\t", args));
    }

    /** Whether the colonist may hold the player to it: reminders, a grudge if broken, no new promise until it's kept. */
    public boolean binding() {
        return confirm == Confirm.CONFIRMED;
    }
}
