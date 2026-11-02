package me.bytefox.livingcolonists.scenarios;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Profile;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Not a test: a probe of how colonists sound with the real model, on the mod's whole pipeline as on the server
 * (profile IMMERSIVE, characters, bios and voices written by the model, dice, moods), with a colony chat that already
 * reads like a novel. Writes the colonists' own talk to {@code build/style-probe-<old|new>.txt}. Needs the key in
 * {@code .env}; skipped without it. Run on purpose: {@code ./gradlew test -PwithSlow --tests '*StyleProbe'}.
 */
@Tag("slow")
class StyleProbe {
    /** Lines from the server's colony chat, 01.11.2026, that read like a novel. */
    private static final List<String[]> LITERARY = List.of(
            new String[] {"Фиона Макнамара", "Говорят, старые яблони по ночам перешёптываются, кто раньше созреет."},
            new String[] {"Хантер Кейси", "Мне нынче снилось, будто я мост строю через реку, а доски сами под руку лезут."},
            new String[] {"Хоуп Макнамара", "Мосты во сне — к переменам, говорят."},
            new String[] {"Дарси Абботт", "Камень к утру иной раз мягче подушки."},
            new String[] {"Джинни Блэквуд", "Камень мягче — позволю себе усомниться, но подушка, что к утру уползает, и вправду мало помогает."},
            new String[] {"Дарси Абботт", "Чайник, конечно — в лампе только свет, а в чайнике чья-то прежняя жизнь."},
            new String[] {"Джинни Блэквуд", "В городе, где я жила прежде, окна выходили на старую мельницу, и по утрам тянуло тёплым зерном."},
            new String[] {"Дарси Абботт", "Тёплое зерно по утрам — это лучше любого вида из окна, пожалуй."});

    @Test
    void colonistsTalkAmongThemselves() throws Exception {
        // The tests run in a folder of their own: the project is somewhere above it.
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (project != null && !Files.exists(project.resolve("gradle.properties"))) {
            project = project.getParent();
        }
        Assumptions.assumeTrue(project != null, "the project folder wasn't found");
        Path env = project.resolve(".env");
        String key = Files.exists(env) ? Files.readAllLines(env).stream().filter(l -> l.startsWith("LIVINGCOLONISTS_API_KEY="))
                .map(l -> l.substring(l.indexOf('=') + 1).strip().replace("\"", "")).findFirst().orElse("") : "";
        Assumptions.assumeTrue(!key.isEmpty(), "no key in .env");
        try (Stage stage = new Stage("Дарси Абботт", "Хантер Кейси", "Хоуп Макнамара", "Джинни Блэквуд", "Фиона Макнамара")) {
            Config.BASE_URL.set("https://api.deepseek.com");
            Config.API_KEY.set(key);
            Config.CHAT_MODEL.set("deepseek-flash");
            Config.PROFILE.set(Profile.IMMERSIVE);
            Config.TIMEOUT_SECONDS.set(60);
            me.bytefox.livingcolonists.Dice.use(new Random());
            // Twice the game's speed: the real model takes 5-20 s, and a talk goes quiet after a minute of game time.
            stage.tickNanos = 25_000_000;
            // Housed and at work, as on a settled server: they chat among themselves instead of asking the player for a home.
            var home = org.mockito.Mockito.mock(com.minecolonies.api.colony.buildings.IBuilding.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS);
            var work = org.mockito.Mockito.mock(com.minecolonies.api.colony.buildings.IBuilding.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS);
            for (int i = 0; i < stage.citizens.size(); i++) {
                stage.body(stage.citizens.get(i), new BlockPos(3 * i, 64, 2));
                org.mockito.Mockito.when(stage.citizens.get(i).getHomeBuilding()).thenReturn(home);
                org.mockito.Mockito.when(stage.citizens.get(i).getWorkBuilding()).thenReturn(work);
            }
            LITERARY.forEach(line -> stage.memory.rememberColony(stage.colony, line[0], line[1]));
            int seeded = stage.chat.size();
            stage.runUntil(() -> colonistLines(stage, seeded).size() >= 12, Duration.ofMinutes(12));
            String prompt = new String(StyleProbe.class.getResourceAsStream("/defaults/prompts/reply-colony.txt").readAllBytes(),
                    StandardCharsets.UTF_8);
            String label = (prompt.contains("not a character in a book") ? "new" : "old") + "-settled";
            Path out = project.resolve("build").resolve("style-probe-" + label + ".txt");
            Files.writeString(out, String.join("\n", colonistLines(stage, seeded)) + "\n", StandardCharsets.UTF_8);
            Config.API_KEY.set("");
            Config.PROFILE.set(Config.PROFILE.getDefault());
            Config.TIMEOUT_SECONDS.set(Config.TIMEOUT_SECONDS.getDefault());
        }
    }

    private static List<String> colonistLines(Stage stage, int from) {
        return stage.chat.stream().skip(from).filter(line -> line.startsWith("<") && !line.startsWith("<Alex>")).toList();
    }
}
