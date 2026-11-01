package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The pages of the promise notebook, from the open promises the server hands it. */
class PromiseBookTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long DAY = BondsService.DAY;

    private static Promise promise(long id, String text, long due, Promise.Confirm confirm) {
        return new Promise(id, PLAYER, "Alex", text, 3, Promise.About.SUPPLY, 0, due, Promise.Status.OPEN, 0, false, false, "",
                confirm);
    }

    private static PromiseBook.Entry entry(Promise promise, long now, long dayTime) {
        return new PromiseBook.Entry("Darcy Abbott", "Riverside", promise, now, dayTime);
    }

    /** Translation keys and literal text on a page, in order. */
    private static List<String> parts(Component page) {
        List<String> parts = new ArrayList<>();
        collect(page, parts);
        return parts;
    }

    private static void collect(Component component, List<String> into) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            into.add(translatable.getKey());
            for (Object arg : translatable.getArgs()) {
                into.add(String.valueOf(arg instanceof Component c ? c.getString() : arg));
            }
        } else {
            into.add(component.getContents().toString());
        }
        component.getSiblings().forEach(sibling -> collect(sibling, into));
    }

    private static List<Style> buttons(Component page) {
        List<Style> buttons = new ArrayList<>();
        page.visit((Style style, String text) -> {
            if (style.getClickEvent() != null) {
                buttons.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return buttons;
    }

    private static String dueKey(long due, long now, long dayTime) {
        Component page = PromiseBook.pages(List.of(entry(promise(1, "Alex promised bread.", due, Promise.Confirm.CONFIRMED),
                now, dayTime))).get(1);
        return parts(page).stream().filter(part -> part.startsWith("livingcolonists.book.due") || part.equals("livingcolonists.book.overdue"))
                .findFirst().orElse("none");
    }

    @Test
    void anEmptyNotebookIsOnePageThatSaysSo() {
        List<Component> pages = PromiseBook.pages(List.of());
        assertEquals(1, pages.size());
        assertTrue(parts(pages.get(0)).contains("livingcolonists.book.empty"));
        assertTrue(buttons(pages.get(0)).isEmpty());
    }

    @Test
    void aFirstPageWithTheCountThenAPagePerPromise() {
        List<Component> pages = PromiseBook.pages(List.of(
                entry(promise(1, "Alex promised bread.", 0, Promise.Confirm.CONFIRMED), 0, 0),
                entry(promise(2, "Alex promised a pickaxe.", 0, Promise.Confirm.CONFIRMED), 0, 0)));
        assertEquals(3, pages.size());
        assertTrue(parts(pages.get(0)).contains("livingcolonists.book.count"));
        assertTrue(parts(pages.get(0)).contains("2"));
        assertTrue(pages.get(1).getString().contains("Alex promised bread."));
        assertTrue(pages.get(2).getString().contains("Alex promised a pickaxe."));
        assertTrue(parts(pages.get(1)).contains("Darcy Abbott"), "to whom");
        assertTrue(pages.get(1).getString().contains("Riverside"), "in which colony");
    }

    @Test
    void deadlinesReadInTheGamesDays() {
        // Morning of a day (dayTime 1000): due later today, tomorrow, in three days; game time and time of day differ.
        long now = 50_000;
        long dayTime = 1000;
        assertEquals("livingcolonists.book.due.today", dueKey(now + 5000, now, dayTime));
        assertEquals("livingcolonists.book.due.tomorrow", dueKey(now + DAY, now, dayTime));
        assertEquals("livingcolonists.book.due.days", dueKey(now + 3 * DAY, now, dayTime));
        // Late evening (dayTime 23000): 2000 ticks on is already tomorrow.
        assertEquals("livingcolonists.book.due.tomorrow", dueKey(now + 2000, now, 23_000));
        assertEquals("livingcolonists.book.overdue", dueKey(now - 1, now, dayTime));
        assertEquals("livingcolonists.book.overdue", dueKey(now, now, dayTime));
        assertEquals("livingcolonists.book.due.none", dueKey(0, now, dayTime));
    }

    @Test
    void aVagueOneCanBeConfirmedAndEveryOneGivenUp() {
        Component vague = PromiseBook.pages(List.of(entry(promise(7, "Alex promised bread.", 0, Promise.Confirm.ASKED), 0, 0))).get(1);
        List<String> commands = buttons(vague).stream().map(style -> style.getClickEvent().getValue()).toList();
        assertEquals(List.of("/lc promise yes 7", "/lc promise no 7"), commands);
        assertTrue(parts(vague).contains("livingcolonists.promise.list.vague"));
        buttons(vague).forEach(style -> {
            assertEquals(ClickEvent.Action.RUN_COMMAND, style.getClickEvent().getAction());
            assertTrue(style.getHoverEvent() != null && style.getHoverEvent().getAction() == HoverEvent.Action.SHOW_TEXT,
                    "a button without a hint");
        });
        Component meant = PromiseBook.pages(List.of(entry(promise(8, "Alex promised bread.", 0, Promise.Confirm.CONFIRMED), 0, 0))).get(1);
        assertEquals(List.of("/lc promise no 8"), buttons(meant).stream().map(style -> style.getClickEvent().getValue()).toList());
        assertFalse(parts(meant).contains("livingcolonists.promise.list.vague"));
    }

    @Test
    void whoGotANotebookIsSavedWithTheWorld() {
        BondsData data = BondsData.factory().constructor().get();
        data.bookGiven.add(PLAYER);
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
        BondsData loaded = BondsData.factory().deserializer().apply(saved, null);
        assertEquals(java.util.Set.of(PLAYER), loaded.bookGiven);
        // A world saved before the notebook existed loads with nobody having one.
        saved.remove("bookGiven");
        assertTrue(BondsData.factory().deserializer().apply(saved, null).bookGiven.isEmpty());
    }

    @Test
    void aLongPromiseIsCutToFitThePage() {
        String longText = "Alex promised " + "to bring a great many things ".repeat(20);
        String page = PromiseBook.pages(List.of(entry(promise(1, longText, 0, Promise.Confirm.CONFIRMED), 0, 0))).get(1).getString();
        assertTrue(page.contains("…"), "not cut");
        assertFalse(page.contains(longText.strip()), "the whole text is on the page");
    }
}
