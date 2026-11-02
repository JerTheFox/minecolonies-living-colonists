package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One colony's conversation in the colony chat, as {@link ColonyThreads} keeps it. Server thread only. */
final class ChatThread {
    /** Bumped by every player message; a line written for an older version is stale. */
    long version;
    int colonistTurns;
    int lastSpeaker = -1;
    long lastLineTick;
    UUID lastPlayer;
    final Deque<Integer> addressed = new ArrayDeque<>();
    /** Colonists the player's latest message was for: each of them answers the player, functions and all. */
    final Set<Integer> askedByPlayer = new HashSet<>();
    final Set<Integer> participants = new HashSet<>();
    /** Topics the dice already turned this thread to. */
    final Set<String> topics = new HashSet<>();
    /** How much life the talk among colonists has left; each line spends some, a question or an argument adds. */
    double energy;
    /** A conversation a colonist starts unprompted: what about, for the opening line. */
    String chatter;
    /** The shape of a talk colonists started themselves (#65); null for news and for talk with a player. */
    TalkShapes.Shape shape;
    /** How it is to end, for the few talks that come to an ending; null when it just fades or it already ended. */
    String ending;
    /** Whether the last line's stance was to disagree, which livens a talk. */
    boolean disagreed;
    /** Whom the colonist speaking now was told to turn to by name: they answer next. */
    int asked = -1;
    /** A colonist just asked a question by name by another colonist: they may look it up (#56). */
    int questionTo = -1;
    /** Lines each colonist said in this thread, so whoever said a lot steps back. */
    final Map<Integer, Integer> lines = new HashMap<>();
    /** A colony event the next line opens the thread with, if any. */
    String opening;
    /** The language the thread is in, like "Russian"; null leaves it to the model. */
    String language;
    long lastEventTick = Long.MIN_VALUE / 2;
    /** World game time the conversation began; earlier chat lines are only context. */
    long startTime;
    /** Who answered the player's last message: a follow-up for nobody in particular goes to them. */
    int lastAnswerer = -1;
    /** Colonists who stayed silent on the player's message so far. */
    int silent;
    /** Server tick and game time of the player's last message. */
    long playerTick = Long.MIN_VALUE / 2;
    long playerGameTime;
    /** Server tick a colonist last answered the player: after {@link #playerTick}, nobody's answer is pending. */
    long answeredTick = Long.MIN_VALUE / 2;
}
