package me.bytefox.livingcolonists.notebook;

import java.util.ArrayList;
import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.errands.Errands;
import me.bytefox.livingcolonists.errands.NoteBook;
import me.bytefox.livingcolonists.errands.NotifyWhen;
import me.bytefox.livingcolonists.network.NotebookPages;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Colony Notebook (#66): the mod's own window, next to MineColonies' books. The server writes its sections for each
 * player every time it is opened or a button in it is used: promises, relations, orders, the colony and help. Buttons
 * run the same {@code /lc} commands as the chat. MineColonies drops our happiness modifiers on the client, so how
 * colonists feel about the player is shown here and not in their happiness window.
 */
public final class Notebook {
    /** One bookmark of the notebook. */
    public record Section(String key, Component title, List<Component> pages) {
    }

    /** About this many lines of text fit on a page. */
    private static final int PAGE_LINES = 15;
    /** And about this many characters on a line. */
    private static final int LINE_CHARS = 21;
    /** Below this happiness a colonist shows up as unhappy. */
    private static final double UNHAPPY = 6.0;

    private final BondsService bonds;
    private final Errands errands;
    private final me.bytefox.livingcolonists.mood.ColonyMoods moods;
    private final me.bytefox.livingcolonists.friendship.Calendar calendar;
    /** Holidays and birthdays shown this many calendar days ahead. */
    private static final int SOON_DAYS = 7;

    public Notebook(BondsService bonds, Errands errands, me.bytefox.livingcolonists.mood.ColonyMoods moods,
                    me.bytefox.livingcolonists.friendship.Calendar calendar) {
        this.bonds = bonds;
        this.errands = errands;
        this.moods = moods;
        this.calendar = calendar;
    }

    /** Opens the notebook for the player, or refreshes it if it is open. */
    public void send(ServerPlayer player, boolean open) {
        PacketDistributor.sendToPlayer(player, new NotebookPages(sections(player), open));
    }

    public List<Section> sections(ServerPlayer player) {
        List<IColony> colonies = Colonies.all().stream()
                .filter(colony -> Colonies.role(player, colony) != ColonyAccess.Role.OTHER).toList();
        return List.of(
                new Section("promises", title("promises"), bonds.notebook(player)),
                new Section("relations", title("relations"), relations(player, colonies)),
                new Section("orders", title("orders"), orders(player)),
                new Section("colony", title("colony"), colony(colonies, player.server.getTickCount())),
                new Section("help", title("help"), help(player, colonies)));
    }

    private static Component title(String key) {
        return Component.translatable("livingcolonists.book.section." + key);
    }

    // ---- sections

    /** Gifts shown per taste in a colonist's relation; a few are enough to pick the next gift. */
    private static final int TASTES_SHOWN = 4;

    private List<Component> relations(ServerPlayer player, List<IColony> colonies) {
        Pager pager = new Pager(title("relations"));
        List<BondsService.PlayerRelation> relations = bonds.relationsOf(player);
        if (relations.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.relations.none"));
        }
        for (BondsService.PlayerRelation relation : relations) {
            MutableComponent block = Component.literal(relation.colonist()).withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("\n").withStyle(style -> style.withBold(false)))
                    .append(Component.translatable(level(relation.rapport())).withStyle(style -> style.withBold(false)));
            if (relation.mood() > 1.02) {
                block.append(Component.literal("\n")).append(Component.translatable("livingcolonists.book.mood.glad")
                        .withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GREEN)));
            } else if (relation.mood() < 0.98) {
                block.append(Component.literal("\n")).append(Component.translatable("livingcolonists.book.mood.hurt")
                        .withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_RED)));
            }
            // What they thought of the player's gifts (#67), so the next one can be picked well.
            for (Taste taste : List.of(Taste.LOVE, Taste.LIKE, Taste.DISLIKE, Taste.HATE)) {
                List<String> items = relation.tastes().getOrDefault(taste, List.of());
                if (items.isEmpty()) {
                    continue;
                }
                MutableComponent names = Component.empty();
                for (int i = 0; i < Math.min(items.size(), TASTES_SHOWN); i++) {
                    names.append(i == 0 ? Component.empty() : Component.literal(", "))
                            .append(BuiltInRegistries.ITEM.get(ResourceLocation.parse(items.get(i))).getDescription());
                }
                block.append(Component.literal("\n")).append(Component.translatable("livingcolonists.book.tastes."
                        + taste.name().toLowerCase(java.util.Locale.ROOT), names).withStyle(style -> style.withBold(false)));
            }
            calendar.birthday(relation.citizen()).ifPresent(day -> {
                var date = new me.bytefox.livingcolonists.friendship.CalendarDate(day);
                block.append(Component.literal("\n")).append(Component.translatable("livingcolonists.book.birthday",
                        season(date.season()), date.dayOfSeason()).withStyle(style -> style.withBold(false)));
            });
            if (colonies.size() > 1) {
                block.append(Component.literal("\n" + relation.colony()).withStyle(style -> style.withBold(false)
                        .withColor(ChatFormatting.DARK_GRAY)));
            }
            pager.add(block);
        }
        List<Component> between = new ArrayList<>();
        for (IColony colony : colonies) {
            List<ICitizenData> citizens = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).toList();
            for (int i = 0; i < citizens.size(); i++) {
                for (int j = i + 1; j < citizens.size(); j++) {
                    int rapport = bonds.colonists().rapport(colony, citizens.get(i).getId(), citizens.get(j).getId());
                    if (Math.abs(rapport) >= me.bytefox.livingcolonists.bonds.Drift.FRIENDS) {
                        between.add(Component.translatable(rapport > 0 ? "livingcolonists.book.relations.friends"
                                : "livingcolonists.book.relations.odds", citizens.get(i).getName(), citizens.get(j).getName()));
                    }
                }
            }
        }
        if (!between.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.relations.between").withStyle(ChatFormatting.BOLD));
            between.forEach(pager::add);
        }
        return pager.pages();
    }

    /** How a colonist feels about the player, by rapport. */
    static String level(int rapport) {
        String level = rapport >= 40 ? "close" : rapport >= 20 ? "friend" : rapport >= 5 ? "likes"
                : rapport > -5 ? "neutral" : rapport > -20 ? "cool" : "odds";
        return "livingcolonists.book.level." + level;
    }

    private List<Component> orders(ServerPlayer player) {
        Pager pager = new Pager(title("orders"));
        errands.waiting(player).ifPresent(order -> pager.add(Component.translatable("livingcolonists.book.order.waiting",
                order.citizen(), order.question()).append("\n")
                .append(button("livingcolonists.order.yes", ChatFormatting.DARK_GREEN, "/lc yes", null)).append(" ")
                .append(button("livingcolonists.order.no", ChatFormatting.DARK_RED, "/lc no", null))));
        errands.lastOrder(player).ifPresent(order -> pager.add(Component.translatable("livingcolonists.book.order.last",
                order.citizen(), order.question()).append("\n")
                .append(button("livingcolonists.book.order.undo", ChatFormatting.DARK_RED, "/lc undo", null))));
        for (NotifyWhen.Watched watch : errands.watches(player)) {
            pager.add(Component.translatable("livingcolonists.book.order.watch", name(watch.colonyId(), watch.citizenId()),
                    watch.wanted(), new ItemStack(watch.item()).getHoverName(), watch.found()));
        }
        for (NoteBook.Note note : errands.notes().of(player.getUUID())) {
            String colonist = name(note.colonyId(), note.citizenId());
            pager.add(note.kind() == NoteBook.Kind.MAIL
                    ? Component.translatable("livingcolonists.book.order.mail", colonist, note.toName(), note.text())
                    : Component.translatable("livingcolonists.book.order.reminder", colonist, note.text()));
        }
        if (pager.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.orders.none"));
        }
        return pager.pages();
    }

    private static String name(int colonyId, int citizenId) {
        IColony colony = Citizens.colony(colonyId);
        ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(citizenId);
        return citizen == null ? "?" : citizen.getName();
    }

    private List<Component> colony(List<IColony> colonies, long now) {
        Pager pager = new Pager(title("colony"));
        for (IColony colony : colonies) {
            if (colonies.size() > 1) {
                pager.add(Component.literal(colony.getName()).withStyle(ChatFormatting.BOLD));
            }
            // The colony's mood (#65): calm most of the time.
            pager.add(Component.translatable("livingcolonists.book.colony.mood."
                    + moods.leading(colony.getID(), now).mood().name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.ITALIC));
            calendar(pager, colony);
            int before = pager.size();
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                try {
                    var mourn = citizen.getCitizenMournHandler();
                    if (mourn != null && mourn.isMourning()) {
                        pager.add(Component.translatable("livingcolonists.book.colony.mourning", citizen.getName(),
                                String.join(", ", mourn.getDeceasedCitizens())));
                    }
                    if (citizen.getCitizenDiseaseHandler() != null && citizen.getCitizenDiseaseHandler().isSick()) {
                        pager.add(Component.translatable("livingcolonists.book.colony.sick", citizen.getName()));
                    }
                    double happiness = citizen.getCitizenHappinessHandler().getHappiness(colony, citizen);
                    if (!citizen.isChild() && happiness > 0 && happiness < UNHAPPY) {
                        pager.add(Component.translatable("livingcolonists.book.colony.unhappy", citizen.getName(),
                                String.format(java.util.Locale.ROOT, "%.1f", happiness)));
                    }
                } catch (RuntimeException | LinkageError e) {
                    // A colonist MineColonies can't tell about now shows next time.
                }
            }
            if (pager.size() == before) {
                pager.add(Component.translatable("livingcolonists.book.colony.calm"));
            }
        }
        if (colonies.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.colony.none"));
        }
        return pager.pages();
    }

    /** The colony's calendar (#67): today, its holiday and birthdays, and what is coming in the next days. */
    private void calendar(Pager pager, IColony colony) {
        var today = calendar.date(colony);
        if (today.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.calendar.not_yet").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        pager.add(Component.translatable("livingcolonists.book.calendar.today", season(today.get().season()),
                today.get().dayOfSeason(), today.get().year()));
        calendar.holiday(colony).ifPresent(holiday -> pager.add(Component.translatable(
                "livingcolonists.book.calendar.holiday_today", holidayName(holiday)).withStyle(ChatFormatting.DARK_GREEN)));
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (calendar.isBirthday(citizen)) {
                pager.add(Component.translatable("livingcolonists.book.calendar.birthday_today", citizen.getName())
                        .withStyle(ChatFormatting.DARK_GREEN));
            }
        }
        List<me.bytefox.livingcolonists.friendship.Calendar.Upcoming> soon = calendar.upcoming(colony, SOON_DAYS);
        if (!soon.isEmpty()) {
            MutableComponent block = Component.translatable("livingcolonists.book.calendar.soon").withStyle(ChatFormatting.BOLD);
            for (var next : soon) {
                String when = next.inDays() == 1 ? "tomorrow" : "soon";
                Component line = next.holiday() != null
                        ? Component.translatable("livingcolonists.book.calendar." + when + "_holiday", holidayName(next.holiday()), next.inDays())
                        : Component.translatable("livingcolonists.book.calendar." + when + "_birthday", next.colonist(), next.inDays());
                block.append(Component.literal("\n")).append(line.copy().withStyle(style -> style.withBold(false)));
            }
            pager.add(block);
        }
    }

    private static Component season(int season) {
        return Component.translatable("livingcolonists.calendar.season." + me.bytefox.livingcolonists.friendship.CalendarDate.SEASONS[season]);
    }

    /** A holiday's name in the player's language; one a server added itself is shown as written. */
    private static Component holidayName(me.bytefox.livingcolonists.friendship.Holiday holiday) {
        return Component.translatableWithFallback("livingcolonists.calendar.holiday." + holiday.id(), holiday.name());
    }

    private static List<Component> help(ServerPlayer player, List<IColony> colonies) {
        Pager pager = new Pager(title("help"));
        pager.add(Component.translatable("livingcolonists.book.help.keys"));
        pager.add(Component.translatable("livingcolonists.book.help.ask"));
        pager.add(Component.translatable("livingcolonists.book.help.orders"));
        pager.add(Component.translatable("livingcolonists.book.help.bubbles"));
        pager.add(button("livingcolonists.book.help.more", ChatFormatting.DARK_BLUE, "/lc help", null));
        if (colonies.stream().anyMatch(colony -> Colonies.role(player, colony) == ColonyAccess.Role.OWNER)) {
            pager.add(Component.translatable("livingcolonists.book.help.colony").append("\n")
                    .append(button("livingcolonists.book.help.colony_off", ChatFormatting.DARK_RED, "/lc colony off",
                            Component.translatable("livingcolonists.book.help.colony_off.hint"))).append(" ")
                    .append(button("livingcolonists.book.help.colony_on", ChatFormatting.DARK_GREEN, "/lc colony on", null)));
        }
        return pager.pages();
    }

    private static Component button(String key, ChatFormatting color, String command, Component hint) {
        return Component.translatable(key).withStyle(style -> {
            var styled = style.withColor(color).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            return hint == null ? styled : styled.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hint));
        });
    }

    /** Lays blocks of text out on pages, the section's title on the first. */
    private static final class Pager {
        private final List<Component> pages = new ArrayList<>();
        private MutableComponent page;
        private int lines;
        private int blocks;

        Pager(Component title) {
            page = Component.empty().append(title.copy().withStyle(ChatFormatting.BOLD)).append("\n\n");
            lines = 2;
        }

        void add(Component block) {
            int height = height(block);
            if (lines + height > PAGE_LINES && blocks > 0) {
                pages.add(page);
                page = Component.empty();
                lines = 0;
            }
            page.append(block).append("\n\n");
            lines += height + 1;
            blocks++;
        }

        boolean isEmpty() {
            return blocks == 0;
        }

        int size() {
            return blocks;
        }

        List<Component> pages() {
            List<Component> all = new ArrayList<>(pages);
            all.add(page);
            return all;
        }

        private static int height(Component block) {
            int lines = 0;
            for (String line : block.getString().split("\n", -1)) {
                lines += Math.max(1, (line.length() + LINE_CHARS - 1) / LINE_CHARS);
            }
            return lines;
        }
    }
}
