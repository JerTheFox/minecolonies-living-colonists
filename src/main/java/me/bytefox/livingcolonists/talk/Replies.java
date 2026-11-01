package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.errands.Errand;
import me.bytefox.livingcolonists.errands.Errands;
import me.bytefox.livingcolonists.perception.ColonyTools;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * One colonist line from the chat model: the prompt and the conversation go out, the colony functions offered to a
 * colonist answering a player run on the server thread and go back with their results, and a line said
 * unprompted that goes round in circles is asked for once more. HTTP runs on the client's threads; results come
 * back through {@link MinecraftServer#execute}.
 */
final class Replies {
    /** Rounds of colony function calls per reply before the model has to answer. */
    static final int MAX_TOOL_ROUNDS = 3;
    /**
     * Token room for a reply: the line is short, but DeepSeek's reasoning sometimes took the whole
     * usual 2000 and cut the line off mid-word.
     */
    static final int REPLY_TOKENS = 4000;
    /** How many of the player's latest lines an order is checked against. */
    static final int RECENT_PLAYER_LINES = 6;

    /** The last reply prompt of each colonist, as {@link #key}, for {@code /lcdebug prompt}. Server thread only. */
    private final Map<Long, String> lastPrompts = new HashMap<>();

    private final TalkService talk;
    private final LlmClient client;
    private final ColonyTools tools;
    private final Errands errands;
    private final MemoryService memory;

    Replies(TalkService talk, LlmClient client, ColonyTools tools, Errands errands, MemoryService memory) {
        this.talk = talk;
        this.client = client;
        this.tools = tools;
        this.errands = errands;
        this.memory = memory;
    }

    /** The last prompt sent for this colonist's reply, if any. */
    Optional<String> lastPrompt(ICitizenData citizen) {
        return Optional.ofNullable(lastPrompts.get(TalkService.key(citizen.getColony().getID(), citizen.getId())));
    }

    void clear() {
        lastPrompts.clear();
    }

    /**
     * {@link #generate}, for a line the colonist says on their own (no player to answer, no tools):
     * a line that goes round in circles ({@link Repeats}) gets one more try, told what was wrong, and
     * is dropped if it still does. Models told not to repeat themselves still do; code catches it.
     *
     * @param recent the latest lines heard where it will be said, newest last
     */
    void generateFresh(ICitizenData citizen, String prompt, List<ChatMessage> conversation, UUID asker,
                       MinecraftServer server, List<String> recent, Consumer<ChatResult> done) {
        generateFresh(citizen, prompt, conversation, asker, false, server, recent, done);
    }

    /** The same; {@code lookups}: a colonist asked by another colonist may look things up once, no orders (#56). */
    void generateFresh(ICitizenData citizen, String prompt, List<ChatMessage> conversation, UUID asker, boolean lookups,
                       MinecraftServer server, List<String> recent, Consumer<ChatResult> done) {
        if (lookups) {
            generate(citizen, prompt, conversation, asker, true, server, done);
            return;
        }
        if (asker != null) {
            generate(citizen, prompt, conversation, asker, server, done);
            return;
        }
        List<String> names = new ArrayList<>(citizen.getColony().getCitizenManager().getCitizens().stream()
                .map(ICitizenData::getName).toList());
        server.getPlayerList().getPlayers().forEach(player -> names.add(Audience.playerName(player)));
        String name = citizen.getName();
        generate(citizen, prompt, conversation, null, server, result -> {
            Optional<String> problem = result instanceof ChatResult.Reply reply && !reply.text().isBlank()
                    ? Repeats.problem(reply.text(), recent, names, true).or(() -> stray(reply.text())) : Optional.empty();
            if (problem.isEmpty()) {
                done.accept(result);
                return;
            }
            boolean english = problem.get().startsWith(STRAY);
            ModLog.action("{}'s line {} ({}), asking again", name, english ? "slipped into English" : "went round in circles",
                    problem.get());
            Counters.add(english ? "english.caught" : "repeats.caught");
            generate(citizen, prompt + "\nYour first try was no good: " + problem.get() + ". Say something different, "
                    + "on another subject if need be, or answer [silence].", conversation, null, server, second -> {
                if (second instanceof ChatResult.Reply reply && Repeats.problem(reply.text(), recent, names, true).isPresent()) {
                    ModLog.action("{}'s second try went round in circles too, said nothing", name);
                    done.accept(new ChatResult.Reply("[silence]", reply.usage()));
                    return;
                }
                done.accept(second);
            });
        });
    }

    private static final String STRAY = "it has the English word";

    /** An English word in a line in another language ("Пока только sketches в голове"), as a reason to ask again. */
    private static Optional<String> stray(String text) {
        return Languages.strayEnglish(text).map(word -> STRAY + " \"" + word + "\" in a line that isn't English: "
                + "every word must be in the line's language");
    }

    /**
     * Asks the chat model for one colonist line. {@code done} gets the result on the server thread.
     *
     * @param asker the player the colonist answers, who may get look-ups and orders; null for none
     */
    void generate(ICitizenData citizen, String prompt, List<ChatMessage> conversation, UUID asker,
                  MinecraftServer server, Consumer<ChatResult> done) {
        generate(citizen, prompt, conversation, asker, false, server, done);
    }

    void generate(ICitizenData citizen, String prompt, List<ChatMessage> conversation, UUID asker, boolean lookups,
                  MinecraftServer server, Consumer<ChatResult> done) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            done.accept(new ChatResult.Failure("no API key", false));
            return;
        }
        Speaker speaker = Audience.speakerOf(citizen);
        lastPrompts.put(TalkService.key(speaker.colonyId(), speaker.citizenId()), prompt);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        messages.addAll(conversation);
        List<ToolSpec> offered = new ArrayList<>();
        if (asker != null && Config.toolsEnabled()) {
            offered.addAll(tools.specs());
            offered.addAll(errands.specs(citizen));
        } else if (lookups && Config.toolsEnabled()) {
            offered.addAll(tools.specs());
            offered.addAll(errands.lookupSpecs(citizen));
        }
        // A line nobody waits for (no player to answer) gives way to players' answers.
        LlmSettings reply = asker == null ? settings.get().withMinTokens(REPLY_TOKENS).inBackground()
                : settings.get().withMinTokens(REPLY_TOKENS);
        // A colonist asked by another colonist gets one look-up at most: the last round offers nothing.
        converse(reply, messages, offered, asker == null && lookups ? MAX_TOOL_ROUNDS - 1 : 0, server,
                citizen.getColony().getDimension(), speaker,
                asker, new StringBuilder(), done);
    }

    /** The cleaned line of a successful reply; empty for failures, empty replies and silence. */
    static Optional<String> lineOf(Speaker speaker, ChatResult result) {
        return switch (result) {
            case ChatResult.Reply reply -> {
                String text = Prompts.cleanReply(reply.text(), speaker.name());
                yield text.isEmpty() || Prompts.isSilence(text) ? Optional.empty() : Optional.of(text);
            }
            case ChatResult.Failure failure -> {
                ModLog.warn("{} could not answer: {}", speaker.name(), failure.reason());
                yield Optional.empty();
            }
        };
    }

    /**
     * One round with the model. When it asks for colony functions, they run on the server thread and
     * the conversation goes back with their results, at most {@link #MAX_TOOL_ROUNDS} times; the last
     * round offers no functions, so the model has to answer. {@code done} runs on the server thread.
     */
    private void converse(LlmSettings settings, List<ChatMessage> messages, List<ToolSpec> offered, int round,
                          MinecraftServer server, ResourceKey<Level> dimension, Speaker speaker, UUID asker,
                          StringBuilder trace, Consumer<ChatResult> done) {
        List<ToolSpec> now = round < MAX_TOOL_ROUNDS ? offered : List.of();
        client.complete(settings, messages, now).thenAccept(result -> server.execute(() -> {
            if (result instanceof ChatResult.Reply written && now.isEmpty() && Prompts.callMarkup(written.text())
                    && round == MAX_TOOL_ROUNDS) {
                // Out of look-ups, the model wrote one more call as text: once more, told to answer in words.
                List<ChatMessage> again = new ArrayList<>(messages);
                again.add(ChatMessage.user("[You can't look anything more up now. Answer in words, from what you found.]"));
                converse(settings, again, offered, round + 1, server, dimension, speaker, asker, trace, done);
                return;
            }
            if (!(result instanceof ChatResult.Reply reply) || !reply.wantsTools()) {
                done.accept(result);
                return;
            }
            ICitizenData citizen = Audience.resolve(dimension, speaker.colonyId(), speaker.citizenId());
            if (citizen == null || now.isEmpty()) {
                done.accept(new ChatResult.Failure("the model asked for functions it wasn't offered", false));
                return;
            }
            List<ChatMessage> next = new ArrayList<>(messages);
            next.add(ChatMessage.assistantCalling(reply.toolCalls()));
            if (asker == null) {
                // A look-up in colonists' own talk costs from the colony's budget like a long line.
                talk.airtime().spend(Airtime.colony(speaker.colonyId()), server.getTickCount(), 0.25);
                Counters.add("lookups.colonists");
            }
            for (ToolCall call : reply.toolCalls()) {
                String output = errands.handles(call.name())
                        ? errands.run(call, errandContext(citizen, server.getPlayerList().getPlayer(asker)))
                        : tools.run(call, citizen);
                trace.append("\n[function] ").append(call.name()).append(call.arguments()).append(" -> ").append(output);
                next.add(ChatMessage.toolResult(call.id(), output));
            }
            lastPrompts.computeIfPresent(TalkService.key(speaker.colonyId(), speaker.citizenId()), (k, v) -> v + trace);
            trace.setLength(0);
            converse(settings, next, offered, round + 1, server, dimension, speaker, asker, trace, done);
        }));
    }

    /** What an errand needs to know: the colonist, the player and what the player wrote lately. */
    private Errand.Context errandContext(ICitizenData citizen, ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        if (player != null) {
            String colonist = citizen.getName();
            String playerName = Audience.playerName(player);
            memory.privateTalk(citizen, player.getUUID()).stream().filter(line -> !line.speaker().equals(colonist))
                    .forEach(line -> lines.add(line.text()));
            memory.colonyChat(citizen.getColony()).stream().filter(line -> line.speaker().equals(playerName))
                    .forEach(line -> lines.add(line.text()));
        }
        return new Errand.Context(citizen, citizen.getColony(), player,
                lines.subList(Math.max(0, lines.size() - RECENT_PLAYER_LINES), lines.size()));
    }

}
