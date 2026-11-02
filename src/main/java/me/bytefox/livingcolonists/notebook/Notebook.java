package me.bytefox.livingcolonists.notebook;

import me.bytefox.livingcolonists.friendship.FriendshipLevels;

import java.util.stream.Collectors;

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
    /**
     * @param news what in the section is worth a mark on its tab until the player looks (open promises, friendship levels,
     *             a holiday coming): the client marks the tab while it differs from what it last saw; "" for nothing
     */
    public record Section(String key, Component title, List<Component> pages, String news) {
    }

    /** About this many lines of text fit on a page. */
    private static final int PAGE_LINES = 15;
    /** And about this many characters on a line. */
    private static final int LINE_CHARS = 23;
    /** Below this happiness a colonist shows up as unhappy. */
    private static final double UNHAPPY = 6.0;

    private final BondsService bonds;
    private final Errands errands;
    private final me.bytefox.livingcolonists.mood.ColonyMoods moods;
    private final me.bytefox.livingcolonists.friendship.Calendar calendar;
    private final me.bytefox.livingcolonists.friendship.Letters letters;
    /** Holidays and birthdays shown this many calendar days ahead. */
    private static final int SOON_DAYS = 7;

    public Notebook(BondsService bonds, Errands errands, me.bytefox.livingcolonists.mood.ColonyMoods moods,
                    me.bytefox.livingcolonists.friendship.Calendar calendar, me.bytefox.livingcolonists.friendship.Letters letters) {
        this.bonds = bonds;
        this.errands = errands;
        this.moods = moods;
        this.calendar = calendar;
        this.letters = letters;
    }

    /** Opens the notebook for the player, or refreshes it if it is open. */
    public void send(ServerPlayer player, boolean open) {
        PacketDistributor.sendToPlayer(player, new NotebookPages(sections(player), open));
    }

    /** Buttons whose command starts with this are the player's own settings, handled by their client, not sent. */
    public static final String CLIENT = "livingcolonists:client ";

    public List<Section> sections(ServerPlayer player) {
        List<IColony> colonies = Colonies.all().stream()
                .filter(colony -> Colonies.role(player, colony) != ColonyAccess.Role.OTHER).toList();
        List<BondsService.PlayerRelation> relations = bonds.relationsOf(player);
        return java.util.stream.Stream.of(
                new Section("promises", title("promises"), bonds.notebook(player), bonds.promiseNews(player)),
                new Section("letters", title("letters"), letters(player), letterNews(player)),
                new Section("relations", title("relations"), relations(player, colonies, relations), relationNews(relations)),
                // Orders come only through the model (#68).
                me.bytefox.livingcolonists.Config.modelOn() ? new Section("orders", title("orders"), orders(player), orderNews(player)) : null,
                new Section("colony", title("colony"), colony(colonies, player.server.getTickCount()), colonyNews(colonies)),
                new Section("settings", title("settings"), settings(player, colonies), ""),
                new Section("help", title("help"), help(), "")).filter(java.util.Objects::nonNull).toList();
    }

    // ---- what is new

    /** Letters with a gift not taken yet. */
    private String letterNews(ServerPlayer player) {
        return letters.of(player.getUUID()).stream().filter(letter -> !letter.taken())
                .map(letter -> Long.toString(letter.id())).collect(Collectors.joining(","));
    }

    /** Letters from colonists, newest first, each with its gift and a button to take it (#67). */
    private List<Component> letters(ServerPlayer player) {
        Pager pager = new Pager(title("letters"));
        List<me.bytefox.livingcolonists.friendship.Letter> list = letters.of(player.getUUID());
        if (list.isEmpty()) {
            pager.add(Component.translatable("livingcolonists.book.letters.none"));
        }
        for (me.bytefox.livingcolonists.friendship.Letter letter : list) {
            MutableComponent block = (letter.from().isEmpty() ? Component.translatable("livingcolonists.book.letters.secret")
                    : Component.translatable("livingcolonists.book.letters.from", letter.from())).withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("\n" + letter.colony()).withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY)))
                    .append(Component.literal("\n").withStyle(style -> style.withBold(false)))
                    .append(Component.translatable("livingcolonists.letter." + letter.kind() + "." + letter.variant())
                            .withStyle(style -> style.withBold(false)));
            if (!letter.gift().isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(letter.gift().getItem()).toString();
                ItemStack shown = letter.gift().copy();
                block.append(Component.literal("\n").withStyle(style -> style.withBold(false)))
                        .append(Component.literal(ICON_SPACE).withStyle(style -> style.withBold(false).withInsertion(ITEM + id)
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(shown)))))
                        .append(Component.empty().append(letter.gift().getHoverName()).append(" ×" + letter.gift().getCount())
                                .withStyle(style -> style.withBold(false)))
                        .append(Component.literal(" ").withStyle(style -> style.withBold(false)))
                        .append(letter.taken() ? Component.translatable("livingcolonists.book.letters.taken")
                                .withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY))
                                : Component.translatable("livingcolonists.book.letters.take").withStyle(style -> style.withBold(false)
                                        .withColor(ChatFormatting.DARK_GREEN)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lc letter take " + letter.id()))));
            }
            pager.add(block);
        }
        return pager.pages();
    }

    /** Each colonist's friendship level and how many of their tastes are known: a milestone or a told favourite is news. */
    private static String relationNews(List<BondsService.PlayerRelation> relations) {
        return relations.stream().map(r -> r.colonist() + ":" + FriendshipLevels.level(r.rapport()).id() + ":"
                + r.tastes().values().stream().mapToInt(List::size).sum()).sorted().collect(Collectors.joining(","));
    }

    private String orderNews(ServerPlayer player) {
        return errands.waiting(player).map(order -> order.question().getString()).orElse("") + "|" + errands.watches(player).size();
    }

    /** A holiday or birthday today or within three days, and who is mourning or ill. */
    private String colonyNews(List<IColony> colonies) {
        List<String> news = new ArrayList<>();
        for (IColony colony : colonies) {
            calendar.holiday(colony).ifPresent(h -> news.add("today " + h.id()));
            calendar.upcoming(colony, NEWS_DAYS).forEach(next -> news.add(next.holiday() != null ? next.holiday().id() : next.colonist()));
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                if (calendar.isBirthday(citizen)) {
                    news.add("birthday " + citizen.getName());
                }
                try {
                    if (citizen.getCitizenMournHandler() != null && citizen.getCitizenMournHandler().isMourning()) {
                        news.add("mourning " + citizen.getName());
                    }
                    if (citizen.getCitizenDiseaseHandler() != null && citizen.getCitizenDiseaseHandler().isSick()) {
                        news.add("ill " + citizen.getName());
                    }
                } catch (RuntimeException | LinkageError e) {
                    // What MineColonies can't tell now isn't news.
                }
            }
        }
        return String.join(",", news);
    }

    private static Component title(String key) {
        return Component.translatable("livingcolonists.book.section." + key);
    }

    // ---- sections

    /** Gifts shown per taste in a colonist's relation; a few are enough to pick the next gift. */
    private static final int TASTES_SHOWN = 6;
    /** A holiday or birthday this many calendar days ahead is news. */
    private static final int NEWS_DAYS = 3;
    /** With more colonists than this, relations show friends only until the player asks for all. */
    static final int FRIENDS_ONLY_ABOVE = 8;
    /** An item drawn as a small icon by the notebook screen, in place of this style's text (#67). */
    public static final String ITEM = "livingcolonists:item ";
    /** Players who asked to see all relations, not only friends; until the server restarts. */
    private final java.util.Set<java.util.UUID> allRelations = new java.util.HashSet<>();

    /** {@code /lc notebook all|friends}: which relations the player sees. */
    public void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("lc").then(net.minecraft.commands.Commands.literal("notebook")
                .then(net.minecraft.commands.Commands.literal("all").executes(context -> {
                    allRelations.add(context.getSource().getPlayerOrException().getUUID());
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("friends").executes(context -> {
                    allRelations.remove(context.getSource().getPlayerOrException().getUUID());
                    return 1;
                }))));
        dispatcher.register(net.minecraft.commands.Commands.literal("lc").then(net.minecraft.commands.Commands.literal("letter")
                .then(net.minecraft.commands.Commands.literal("take").then(net.minecraft.commands.Commands.argument("id",
                        com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(context ->
                        letters.take(context.getSource().getPlayerOrException(),
                                com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "id")) ? 1 : 0)))));
    }

    /** Whether the player sees all relations or friends only. */
    public void showAll(ServerPlayer player, boolean all) {
        if (all) {
            allRelations.add(player.getUUID());
        } else {
            allRelations.remove(player.getUUID());
        }
    }

    private List<Component> relations(ServerPlayer player, List<IColony> colonies, List<BondsService.PlayerRelation> all) {
        Pager pager = new Pager(title("relations"));
        List<BondsService.PlayerRelation> relations = all;
        if (all.size() > FRIENDS_ONLY_ABOVE) {
            boolean everyone = allRelations.contains(player.getUUID());
            int friendRank = FriendshipLevels.byId("friend").map(FriendshipLevels.Level::rank).orElse(Integer.MAX_VALUE);
            if (!everyone) {
                relations = all.stream().filter(r -> FriendshipLevels.level(r.rapport()).rank() >= friendRank).toList();
            }
            pager.add(Component.empty()
                    .append(filter("livingcolonists.book.relations.friends_only", !everyone, "/lc notebook friends", relations.size()))
                    .append(" ")
                    .append(filter("livingcolonists.book.relations.everyone", everyone, "/lc notebook all", all.size())));
            if (relations.isEmpty()) {
                pager.add(Component.translatable("livingcolonists.book.relations.no_friends"));
            }
        }
        if (all.isEmpty()) {
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
                // Small item icons, as on the tabs; the name shows on hover.
                MutableComponent names = Component.empty();
                for (int i = 0; i < Math.min(items.size(), TASTES_SHOWN); i++) {
                    String id = items.get(i);
                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
                    names.append(Component.literal(ICON_SPACE).withStyle(style -> style.withInsertion(ITEM + id)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(stack)))));
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
            // Between colonists starts a page of its own, apart from how they feel about the player.
            if (!pager.isEmpty()) {
                pager.newPage();
            }
            pager.add(Component.translatable("livingcolonists.book.relations.between").withStyle(ChatFormatting.BOLD));
            between.forEach(pager::add);
        }
        return pager.pages();
    }

    /** How a colonist feels about the player, by rapport. */
    static String level(int rapport) {
        return "livingcolonists.book.level." + me.bytefox.livingcolonists.friendship.FriendshipLevels.level(rapport).id();
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

    /** The topics of the help section, in order: each a title and a few lines. */
    private static final List<String> HELP = List.of("talk", "who", "ask", "orders", "promises", "gifts", "friendship",
            "calendar", "mood");

    /** Without a model the help starts with the talk menu and leaves out what needs the model. */
    private static final List<String> HELP_NO_MODEL = List.of("menu", "gifts", "friendship", "calendar", "mood");

    private static List<Component> help() {
        Pager pager = new Pager(title("help"));
        for (String topic : me.bytefox.livingcolonists.Config.modelOn() ? HELP : HELP_NO_MODEL) {
            pager.add(Component.translatable("livingcolonists.book.help." + topic + ".title").withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("\n")).append(Component.translatable("livingcolonists.book.help." + topic)
                            .withStyle(style -> style.withBold(false))));
        }
        pager.add(button("livingcolonists.book.help.more", ChatFormatting.DARK_BLUE, "/lc help", null));
        return pager.pages();
    }

    /** The player's own settings, the colony's switch for its owner, and how the server is set up for talking. */
    private static List<Component> settings(ServerPlayer player, List<IColony> colonies) {
        Pager pager = new Pager(title("settings"));
        pager.add(Component.translatable("livingcolonists.book.settings.lines").append("\n")
                .append(button("livingcolonists.book.settings.lines.on", ChatFormatting.DARK_GREEN, CLIENT + "lines on", null))
                .append(" ")
                .append(button("livingcolonists.book.settings.lines.off", ChatFormatting.DARK_BLUE, CLIENT + "lines off",
                        Component.translatable("livingcolonists.book.settings.lines.off.hint"))));
        if (colonies.stream().anyMatch(colony -> Colonies.role(player, colony) == ColonyAccess.Role.OWNER)) {
            pager.add(Component.translatable("livingcolonists.book.help.colony").append("\n")
                    .append(button("livingcolonists.book.help.colony_off", ChatFormatting.DARK_RED, "/lc colony off",
                            Component.translatable("livingcolonists.book.help.colony_off.hint"))).append(" ")
                    .append(button("livingcolonists.book.help.colony_on", ChatFormatting.DARK_GREEN, "/lc colony on", null)));
        }
        pager.add(Component.translatable(me.bytefox.livingcolonists.Config.modelOn()
                ? "livingcolonists.book.settings.model_on" : "livingcolonists.book.settings.model_off"));
        pager.add(Component.translatable(me.bytefox.livingcolonists.Config.MEMBERS_ONLY.get()
                ? "livingcolonists.book.settings.members_only" : "livingcolonists.book.settings.everyone"));
        if (me.bytefox.livingcolonists.Config.COLONY_CHAT_ANYWHERE.get()) {
            pager.add(Component.translatable("livingcolonists.book.settings.anywhere"));
        }
        pager.add(button("livingcolonists.book.settings.all", ChatFormatting.DARK_BLUE, CLIENT + "settings",
                Component.translatable("livingcolonists.book.settings.all.hint")));
        return pager.pages();
    }

    /** Room for one small item icon in a line of text. */
    public static final String ICON_SPACE = "   ";

    /** A filter button for relations: the one in use is bold and does nothing. */
    private static Component filter(String key, boolean current, String command, int count) {
        MutableComponent label = Component.translatable(key, count);
        return current ? label.withStyle(ChatFormatting.BOLD)
                : label.withStyle(style -> style.withColor(ChatFormatting.DARK_BLUE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
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

        /** What comes next starts on a fresh page. */
        void newPage() {
            pages.add(page);
            page = Component.empty();
            lines = 0;
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
                // Wrapped by words, as the screen wraps them.
                int used = 0;
                int count = 1;
                for (String word : line.split(" ")) {
                    if (used > 0 && used + 1 + word.length() > LINE_CHARS) {
                        count++;
                        used = 0;
                    }
                    used += (used > 0 ? 1 : 0) + word.length();
                }
                lines += count;
            }
            return lines;
        }
    }
}
