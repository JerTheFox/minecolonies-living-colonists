package me.bytefox.livingcolonists.settings;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Checks a value typed on the settings screen before it goes into the config. */
public final class SettingValues {
    /** The value to store, normalized, or why it can't be stored. */
    public record Checked(String value, String error) {
        public boolean isError() {
            return error != null;
        }

        static Checked ok(String value) {
            return new Checked(value, null);
        }

        static Checked bad(String error) {
            return new Checked(null, error);
        }
    }

    private SettingValues() {
    }

    public static Checked check(Setting.Kind kind, String raw) {
        String text = raw == null ? "" : raw.strip();
        return switch (kind.type()) {
            case BOOL -> text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")
                    ? Checked.ok(text.toLowerCase(Locale.ROOT)) : Checked.bad("expected true or false");
            case INT, LONG -> {
                try {
                    long number = Long.parseLong(text);
                    yield number < kind.min() || number > kind.max() ? Checked.bad(range(kind))
                            : Checked.ok(String.valueOf(number));
                } catch (NumberFormatException e) {
                    yield Checked.bad("expected a whole number, " + range(kind));
                }
            }
            case DOUBLE -> {
                try {
                    double number = Double.parseDouble(text.replace(',', '.'));
                    yield number < kind.min() || number > kind.max() || Double.isNaN(number) ? Checked.bad(range(kind))
                            : Checked.ok(String.valueOf(number));
                } catch (NumberFormatException e) {
                    yield Checked.bad("expected a number, " + range(kind));
                }
            }
            case CHOICE -> kind.choices().contains(text) ? Checked.ok(text)
                    : Checked.bad("expected one of " + String.join(", ", kind.choices()));
            case JSON -> {
                try {
                    yield JsonParser.parseString(text.isEmpty() ? "{}" : text).isJsonObject()
                            ? Checked.ok(text.isEmpty() ? "{}" : text) : Checked.bad("expected a JSON object, like {}");
                } catch (JsonParseException e) {
                    yield Checked.bad("not valid JSON");
                }
            }
            case URL -> {
                String url = text.replaceAll("/+$", "");
                try {
                    URI uri = URI.create(url);
                    yield ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                            ? Checked.ok(url) : Checked.bad("expected an http(s) address");
                } catch (IllegalArgumentException e) {
                    yield Checked.bad("expected an http(s) address");
                }
            }
            case LIST -> Checked.ok(Arrays.stream(raw == null ? new String[0] : raw.split("\\R"))
                    .map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n")));
            case TEXT, SECRET -> Checked.ok(text);
        };
    }

    private static String range(Setting.Kind kind) {
        String max = kind.max() >= Long.MAX_VALUE ? "no limit" : format(kind.max());
        return "from " + format(kind.min()) + " to " + max;
    }

    private static String format(double number) {
        return number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
    }
}
