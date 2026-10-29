package me.bytefox.livingcolonists.talk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What colonists call players instead of their nicknames, from config entries like
 * {@code JerTheFox=Максим,Макс,Максик}: the most common form first, the rarest last.
 */
public final class PlayerNames {
    /** Share of replies in which a colonist may use the player's name. */
    public static final double NAME_CHANCE = 0.3;

    private PlayerNames() {
    }

    /** Lower-case nickname to names; broken entries are skipped. */
    public static Map<String, List<String>> parse(List<? extends String> entries) {
        Map<String, List<String>> names = new HashMap<>();
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String nickname = entry.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            List<String> forms = Arrays.stream(entry.substring(equals + 1).split(","))
                    .map(String::strip).filter(name -> !name.isEmpty()).toList();
            if (!nickname.isEmpty() && !forms.isEmpty()) {
                names.put(nickname, forms);
            }
        }
        return names;
    }

    /** The name colonists know the player by: the first configured form, or the nickname. */
    public static String name(String nickname, Map<String, List<String>> names) {
        List<String> forms = names.get(nickname.toLowerCase(Locale.ROOT));
        return forms == null ? nickname : forms.get(0);
    }

    /**
     * A prompt line on how to address the player in this one reply. A model can't keep "only now
     * and then" across replies it doesn't remember, so the code decides: in about
     * {@link #NAME_CHANCE} of replies it may use one name, picked with the first ones likelier;
     * otherwise it must not use any.
     */
    public static String addressing(String nickname, Map<String, List<String>> names, Random random) {
        if (random.nextDouble() >= NAME_CHANCE) {
            return "Don't use the player's name in this reply.";
        }
        List<String> forms = names.get(nickname.toLowerCase(Locale.ROOT));
        String name = forms == null ? nickname : pick(forms, random);
        return "If it fits naturally, you may call the player " + name + " once in this reply, not at the start.";
    }

    /** One of the forms, the first ones likelier: weights n, n-1, ..., 1. */
    static String pick(List<String> forms, Random random) {
        int total = forms.size() * (forms.size() + 1) / 2;
        int roll = random.nextInt(total);
        for (int i = 0; i < forms.size(); i++) {
            roll -= forms.size() - i;
            if (roll < 0) {
                return forms.get(i);
            }
        }
        return forms.get(0);
    }
}
