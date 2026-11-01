package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.memory.PlayerMarks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * The pages of a player's promise notebook (#60): a first page with the count, then one page per open promise with its
 * deadline and buttons that run the same commands as the chat ({@code /lc promise yes|no <id>}). Built by the server
 * each time the book is opened or a promise in it changes.
 */
final class PromiseBook {
    /** Long promises are cut so a page never runs off the paper. */
    private static final int MAX_TEXT = 150;

    /**
     * One open promise, with what the page needs to know about where it was made.
     *
     * @param now     world game time, the clock promises are kept by
     * @param dayTime the time of day now ({@code Level.getDayTime}), so deadlines read in the game's calendar days
     */
    record Entry(String colonist, String colony, Promise promise, long now, long dayTime) {
    }

    private PromiseBook() {
    }

    static List<Component> pages(List<Entry> entries) {
        List<Component> pages = new ArrayList<>();
        MutableComponent first = Component.translatable("livingcolonists.book.title").withStyle(ChatFormatting.BOLD)
                .append(Component.literal("\n\n").withStyle(style -> style.withBold(false)));
        if (entries.isEmpty()) {
            pages.add(first.append(Component.translatable("livingcolonists.book.empty").withStyle(style -> style.withBold(false))));
            return pages;
        }
        pages.add(first.append(Component.translatable("livingcolonists.book.count", entries.size())
                .withStyle(style -> style.withBold(false))));
        for (Entry entry : entries) {
            pages.add(page(entry));
        }
        return pages;
    }

    private static Component page(Entry entry) {
        Promise promise = entry.promise();
        String text = PlayerMarks.resolve(promise.text());
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT - 1).strip() + "…";
        }
        MutableComponent page = Component.empty()
                .append(Component.translatable("livingcolonists.book.to", entry.colonist()).withStyle(ChatFormatting.BOLD))
                .append("\n")
                .append(Component.literal(entry.colony()).withStyle(ChatFormatting.DARK_GRAY))
                .append("\n\n")
                .append(Component.literal(text))
                .append("\n\n")
                .append(due(promise, entry.now(), entry.dayTime()))
                .append("\n\n");
        if (!promise.binding()) {
            page.append(Component.translatable("livingcolonists.promise.list.vague").withStyle(ChatFormatting.ITALIC))
                    .append("\n")
                    .append(button("livingcolonists.promise.ask.yes", ChatFormatting.DARK_GREEN, "/lc promise yes " + promise.id(),
                            Component.translatable("livingcolonists.promise.ask.yes.hint", entry.colonist())))
                    .append("\n");
        }
        return page.append(button("livingcolonists.promise.give_up", ChatFormatting.DARK_RED, "/lc promise no " + promise.id(),
                Component.translatable("livingcolonists.promise.give_up.hint")));
    }

    private static Component due(Promise promise, long now, long dayTime) {
        if (promise.due() <= 0) {
            return Component.translatable("livingcolonists.book.due.none").withStyle(ChatFormatting.DARK_GRAY);
        }
        if (promise.due() <= now) {
            return Component.translatable("livingcolonists.book.overdue").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        }
        // "Tomorrow" means the next day in the game, not 24000 ticks from now.
        long days = Math.floorDiv(promise.due() - now + dayTime, BondsService.DAY) - Math.floorDiv(dayTime, BondsService.DAY);
        return switch ((int) Math.min(days, Integer.MAX_VALUE)) {
            case 0 -> Component.translatable("livingcolonists.book.due.today");
            case 1 -> Component.translatable("livingcolonists.book.due.tomorrow");
            default -> Component.translatable("livingcolonists.book.due.days", days);
        };
    }

    private static Component button(String key, ChatFormatting color, String command, Component hint) {
        return Component.translatable(key).withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hint)));
    }
}
