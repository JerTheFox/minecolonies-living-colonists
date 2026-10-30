<p align="center"><img src=".github/logo.png" alt="Living Colonists logo" width="160"></p>

# MineColonies: Living Colonists

A [MineColonies](https://github.com/ldtteam/minecolonies) addon for NeoForge that lets colonists talk in text chat almost like other players. They start conversations, answer you, remember what you told them and gossip about the colony with each other. The words come from a language model behind any OpenAI-compatible API: DeepSeek, Gemini, OpenRouter, or a model running on your own machine.

> **Status: first release, 0.1.0.** Everything below works, but it has only just started being played on a live server. Expect rough edges, and please report them in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## What it feels like

Colonists write in chat just like players do; hover over a name to see the profession and colony. You walk into your colony and see lines in chat and speech bubbles over their heads:

```
<John Brown> flour for two more days. Is anyone actually working the fields?
<Clodagh Bennett> John, look at yourself, the oven's been cold for three days
```

You look at the baker and press a key: the chat opens, and a line above the input shows "→ John Brown". From now on whatever you type goes to him. You ask what the oven needs, and a second or two later he answers, going by what's actually in his storage and what he remembers about you. You ask a courier to bring coal from the warehouse to the bakery: the courier asks you to confirm, walks to the warehouse, takes the coal and carries it over. Half an hour on, he messages you himself: "You promised firewood. Where's the firewood?"

## Features

- Colonists speak first, reply and talk among themselves, but only while a player is in the colony, so an empty server spends no tokens.
- They know their situation from MineColonies itself: job, hunger, housing, happiness, time of day, weather, who is around, raids, construction, new citizens.
- Memory of recent lines and events, plus long-term facts like "Max promised to build me a house". It is stored with the world and goes into your backups.
- Mood sets the tone: a hungry colonist grumbles, a homeless one complains, one who just survived a raid is shaken.
- Errands go through the colony's own systems: fetching, delivering, crafting requests and more, based on [Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands).
- Talk to the whole colony or to one colonist privately; colonists understand names and professions in any language.
- Speech bubbles over colonists' heads: white pixel comic bubbles, faint behind walls nearby, with bouncing dots while they type; optionally over players too.
- Every colonist gets a personality: an archetype picked mostly from their MineColonies skills, with a dash of chance, and a short background written once by the model. Archetypes and prompts are plain files you can edit, add or delete.
- Memory profiles from *minimal* (just orders and answers, cheapest) to *immersive* (colonists remember much more), with old memories fading out.
- Only colony members (owner, officers, friends) can talk to colonists; only owners and officers can give orders. Can be turned off.
- Any OpenAI-compatible provider, with separate models for chat and for writing characters, retries, a fallback endpoint and token usage statistics.
- An in-game settings screen, and `/lcdebug` commands for server operators.

## Requirements

Versions are pinned exactly. MineColonies addons break across MineColonies builds, so other versions are not supported.

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| MineColonies | 1.1.1405 |
| Structurize | 1.0.835 |
| BlockUI | 1.0.209 |
| Domum Ornamentum | 1.0.236 |

The mod is needed on the server and on every player's client: a client without it can't join, and the game says what is missing.

## Installation

Download the jar from [Releases](https://github.com/JerTheFox/minecolonies-living-colonists/releases) and put it into the `mods` folder of the server and of every client, like any other mod. Then set up the language model (see Configuration).

## Configuration

The language model is set up on the server (in single-player, on your own machine). The easiest way is the settings screen: the *Config* button next to the mod in the mod list, or the *Living Colonists settings* key (unbound by default, see Controls). It has every option below on four tabs (Connection, Behaviour, Limits, Usage), checks values before saving, can test the chat and character models and load the provider's model list. Changes apply at once, no restart. In a world it edits the server's settings: operators (and the owner of a single-player world) can change them, other players only look; from the main menu it edits the local file. API keys are never shown or sent to players: the screen only says whether one is set, and a newly typed key goes to the server once. Mods like Configured show the same options with names and tooltips.

Or edit the file by hand: open `config/livingcolonists-common.toml` (it appears after the first launch), put your provider's key into `apiKey` and adjust the rest of the `[llm]` section if needed. The key is stored there as plain text, so don't share the file. Server owners who prefer not to keep it in a file can leave `apiKey` empty and set the environment variable `LIVINGCOLONISTS_API_KEY` instead.

| Option | Default | Meaning |
|---|---|---|
| `baseUrl` | `https://api.deepseek.com` | OpenAI-compatible API base URL |
| `chatModel` | `deepseek-flash` | model for replies, as the provider's `/models` lists it |
| `characterModel` | empty | optional smarter model for backgrounds and memory summaries, e.g. `deepseek-v4-pro`; empty uses `chatModel` |
| `apiKey` | empty | your API key |
| `apiKeyEnv` | `LIVINGCOLONISTS_API_KEY` | environment variable read when `apiKey` is empty |
| `timeoutSeconds` | `30` | how long to wait for a reply |
| `retries` | `3` | retries after timeouts, lost connections, rate limits and server errors |
| `useTools` | `true` | let colonists look things up and take orders through function calling; turn off if your provider doesn't support tools |
| `maxTokens` | `2000` | token limit per request, including the model's thinking |
| `temperature` | `1.0` | higher is more varied |
| `extraParams` | `{}` | extra JSON merged into each request, e.g. `{"top_p": 0.9}`; better not to turn off the model's thinking: without it colonists make up facts |

The `[llm.fallback]` section sets up a second API used when the main one fails even after retries, for example a local model: `enabled`, `baseUrl`, `model`, `apiKey` (may stay empty for local models), `apiKeyEnv`.

The optional `[embeddings]` section lets colonists recall the remembered facts a talk touches by meaning, in any language: "is your leg better?" finds "Ephraim's knee aches" though they share no word. Without it, facts are picked by the people in the talk, the words in common, importance and age. It takes an OpenAI-compatible `/embeddings` API: `baseUrl` (default OpenRouter, `https://openrouter.ai/api/v1`), `model` (default `baai/bge-m3`: multilingual, about $0.01 per million tokens, a fraction of a cent a day), `apiKey` and `apiKeyEnv` (default `LIVINGCOLONISTS_EMBEDDINGS_API_KEY`). Embeddings are on when a key is found, or when `baseUrl` is on this machine (for example Ollama with `bge-m3`). The model must know your players' languages; English-only models can't match Russian messages with English facts.

The `[behaviour]` section sets how colonists behave:

| Option | Default | Meaning |
|---|---|---|
| `profile` | `DEFAULT` | `MINIMAL` (the colony run by chat: plain answers, look-ups and orders, no character, memory or chatter; cheapest), `LIGHT`, `DEFAULT` or `IMMERSIVE` (colonists remember much more) |
| `personality`, `perception`, `memory`, `conversation`, `initiative` | `profile` | override one module: `off`, `low`, `default` or `high`; `conversation` sets how long colony chats run on their own, `initiative` how often colonists write to you first |
| `speechBubbles` | `true` | colonists' lines also as speech bubbles over their heads, with dots while they type |
| `playerBubbles` | `false` | speech bubbles and typing dots over players too |
| `bubbleRange` | `24` | how far away bubbles are seen, in blocks |
| `cooldownSeconds` | `3` | shortest time between one player's messages to colonists |
| `playerNames` | empty | what colonists call players instead of nicknames, e.g. `["Steve42=Steven,Steve,Stevie"]`, most common form first |
| `colonyChatAnywhere` | `false` | colony members read and write their colony's chat from anywhere, not only inside its borders |
| `membersOnly` | `true` | only the colony's owner, officers and friends may talk to its colonists; only owners and officers may give orders |

The optional `[limits]` section guards public servers against chat spam and runaway costs; `0` turns a limit off, and both are off by default: `messagesPerPlayerPerHour` (messages one player may send to colonists in an hour) and `tokensPerDay` (tokens the whole server may spend in a day; then colonists rest until midnight).

### Personalities and prompts

Every colonist gets an archetype (grumbler, chatterbox, quiet one, gossip, workhorse, idler, dreamer, optimist, worrier, joker, thinker), picked mostly from their MineColonies skills with a share of chance, and a short background the model writes once. Children get a description of their temperament, sometimes take after a parent, and get an adult background when they grow up.

Archetypes live in `config/livingcolonists/archetypes/` as JSON files: `name`, `prompt` (how the colonist behaves), `weight` (how common), `talkativeness` (0 to 1) and `contrariness` (0 to 1, how often they disagree) and `skills` (skill name to affinity, e.g. `{"Strength": 0.6, "Intelligence": -0.4}`). Add a file to add an archetype, delete one to remove it; delete the folder to get the defaults back.

Colonists also know their situation from MineColonies: their job and what work they can't do, home, hunger and food, health and sickness, happiness and its causes, what their workplace waits for, the weather, the colony, a raid, and how their family and coworkers are doing. The `perception` level sets how much of it they know. Unless `perception` is `off`, colonists can also look things up while answering: facts about another colonist, who fits a profession best by its skills, and an overview of the colony. Ask "who would make a good builder?" and the answer comes from the real skills, not a guess.

Colonists remember. Recent lines of each private talk and of the colony chat go back to the model as they are. Once a conversation goes quiet, the character model writes down what the colonists who took part would remember a few days later: a promise, a plan, news, how someone felt. Every fact gets an importance; trivia fades in a day or two, big news lasts much longer. The `memory` level sets how many facts a colonist keeps and for how long. Now and then a colonist passes on, as gossip, something another player told them in private; what you asked to keep secret, they keep.

Promises count. Promise a colonist planks by tomorrow or a house within a week, and they remember the deadline, remind you when it has passed, thank you when you keep it and hold it against you when you don't. A promised home, job, treatment or building is noticed by itself once it happens. Answering a colonist's request ("I need a pickaxe") with "I will work on it!" counts as a promise too, due in a day and kept once the request is delivered. Colonists also remember whether you are kind or rude to them, and the colony talks: a player rude to several colonists gets a reputation.

Colonists talk like ordinary people, not eager helpers: short lines, no forced cheer, and a mood for the day that the code rolls (calm, tired, irritable, distracted, bored, gloomy or in a good mood; unhappy colonists are in a bad mood more often). Colonists have their own life too. They remember who they get along with and who gets on their nerves, so the same pairs side with each other or argue. When something happens in the colony (a death, a birth, a child growing up, a newcomer, a finished building, a raid) and players are around, a colonist brings it up in the colony chat and others join in. A promise past its deadline gets you a private reminder from the colonist waiting for it.

Prompt templates live in `config/livingcolonists/prompts/`: `reply-private.txt`, `reply-colony.txt`, `character.txt`, `background.txt`, `route-colony.txt`, `bio.txt`, `bio-child.txt`, `bio-grownup.txt`, `memory.txt`, `memory-player.txt`, `memory-colonists.txt`, `voice.txt`. Placeholders like `{name}`, `{job}`, `{character}` and `{situation}` are filled in by the mod. Files you haven't edited follow the mod's defaults when it updates; delete a file to restore its default. `/lcdebug reload` rereads both folders.

### Commands for operators

`/lcdebug` needs operator rights on a server, or cheats turned on in single-player. Regular players only have `/lc`. Without a name, colonist commands act on your conversation partner, or else on the colonist you look at.

| Command | What it does |
|---|---|
| `/lcdebug stats` | requests and tokens per model, this run and in total |
| `/lcdebug profile [name]` | show or change the profile |
| `/lcdebug colonist [name]` | archetype, background, skills and what the colonist knows |
| `/lcdebug archetype <id\|reroll> [name]` | set or re-pick an archetype |
| `/lcdebug bio regenerate [name]` | write a new background |
| `/lcdebug prompt [name]` | the last prompt sent for this colonist's reply |
| `/lcdebug memory show\|clear [name]` | a colonist's facts and private talks, or make them forget |
| `/lcdebug bonds show\|clear [name]` | a colonist's promises from players and how each player treats them, or forget them |
| `/lcdebug relations [name\|all]` | how a colonist, or every pair in the colony you stand in, gets along |
| `/lcdebug memory digest` | write down every conversation waiting to be noted, right away |
| `/lcdebug thread [break]` | the colony chat thread and its recent lines; `break` stops it |
| `/lcdebug orders` | the latest orders: asked, done, turned down, cancelled, taken back |
| `/lcdebug reload` | reread archetypes and prompts |

## Usage

Colonists listen while you are inside the colony's borders. Three keys cover the chat:

| Key | What it does |
|---|---|
| **T** | the usual chat with players (it also leaves colony mode or a private talk) |
| **Y** | write to the whole colony |
| **U** | talk privately to the colonist you look at |

- **The whole colony.** Name a colonist and they answer first; name a profession ("builders, when will you finish?") and those colonists answer; ask about something one profession looks after (food, the warehouse, buildings, research, defence) and they answer; a follow-up goes to whoever answered you last. While you talk, others join in only now and then, and wait a few seconds so you can answer first; after a look-up or an order the chat mostly stays quiet. When you fall silent, colonists talk among themselves: they may argue, answer each other or let it die down, and drift to another topic. Players inside the colony see it.
- **One colonist, privately.** Whatever you type goes to that colonist only, like a direct message, wherever in the colony you are; such lines are marked `<You> → <Colonist>` in chat. They keep working while you talk, and remember your recent talks. Clicking a colonist's name in chat does the same. Press **U** while looking at nobody, or click ✕ above the chat input, to end it.

For colony members (owner, officers, friends), a *Players | Colony* switch above the chat input shows where your message goes and can be clicked. Unbound by default: a key to switch the mode, and *Focus on colonist*, which picks a private partner without opening the chat. All keys can be rebound in Controls. Colonists take a moment to answer, as if reading and typing: after the reading pause, "Idris is typing..." shows above the hotbar (or next to the switch while the chat is open). Colonists' names are shown in their colony's color, and so are the arrow and addressee of private lines. Colony and private lines are written to the server log, like player chat.

Colonists also write to you first now and then, privately, at uneven times, while you are in the colony and not in a conversation: about a real problem of theirs (more pointed if it stays unsolved, until they give up asking), something you told them earlier, a bit of news, or just small talk.

Colonists talk about their real day: the mod notes what each one did (worked at their workplace, slept at home or outside, ate, hid from a raid), so "how was your day?" gets a true answer and they have something of their own to bring up. Each colonist also hopes for one thing the colony can make come true (their house or workplace a level up, a hospital or a tavern); they mention it now and then, never in every line, and tell you gladly once it happens. A few sample lines written from each colonist's background give them a voice of their own. A line that only says again what was said lately, or comes back once more to the same thing, is caught by the mod and asked for again. Asked how the colony works ("will you take food from a chest?"), colonists answer from a short handbook of MineColonies' mechanics instead of guessing, and the names of buildings and items come in the players' language.

Colonists can also look things up and take orders: ask "how much iron do we have?" or "Idris, upgrade the bakery". They know the stock, the food, what is being built, the research, who is unhappy, who died, whether the guards are armed, what the colony needs from you, and where someone or something is; they can take a job, order a craft, build or upgrade a building and move it up the builders' queue, arm the guards, plant a field, start a research, add a dish to the restaurant's menu, sort the warehouse, move into another home, tell you when they find something, pass a message on to another player when they come to the colony, and remind you of something later. They come over, follow you, wait, go somewhere, gather, go home or lead you the way; couriers bring you things from the warehouse or take them to a building; guards escort you, hold your spot, go on alert, patrol a spot, stand down, and keep a tally of their kills. Look-ups answer at once. Orders come only from the colony's owner and officers (a friend gets turned down), only from your own words, and wait for your yes: a question with *Yes* and *No* buttons shows in chat, and a short "yes" or "no" in chat works too. `/lc undo` takes your last order back where it can be taken back.

The chat's buttons run commands you can also type: `/lc yes` and `/lc no` answer a colonist's order question, `/lc undo` takes your last order back, and `/lc lock` starts a private talk with the colonist you look at.

## Building from source

You need JDK 21.

For the dev runs, copy `.env.example` to `.env` and put your API key there; `runClient` and `runServer` pass it to the game.

```bash
./gradlew build      # jar ends up in build/libs/
./gradlew runClient  # development client with MineColonies, single-player world
./gradlew test       # unit tests
./gradlew runServer  # local dedicated server for network testing
```

## Translations

The mod follows the game's language setting. English and Russian are included. To add a language, copy [`en_us.json`](src/main/resources/assets/livingcolonists/lang/en_us.json) to your locale code (for example `de_de.json`) in the same folder, translate the values and open a pull request. Colonists answer in the language you write to them in, whatever the client language is.

## Roadmap

1. ✅ A command → a colonist → an AI reply in chat.
2. ✅ Talking without commands: the whole colony or one colonist privately.
3. ✅ Personalities, perception and memory: archetypes, backgrounds, memory, promises and relations, `/lcdebug`.
4. ✅ In-game settings screen.
5. ✅ Errands through the colony's systems: look-ups, orders confirmed with Yes / No, walks, couriers, guards, mail and reminders.
6. ✅ Colonists speak first, at uneven times and taking turns.
7. ✅ Colonists talk to each other.
8. ✅ Speech bubbles.

Next: playing 0.1.0 on a live server and fixing what comes up; then deadlines read by code and gifts noticed in a colonist's inventory.

## About this project

I'm not a programmer. This mod is written with the help of [Claude](https://claude.ai), Anthropic's AI assistant: I set the direction, test in game and make the decisions, and Claude writes most of the code.

It started as a private mod for my home server. We had been running Talking Colonists with Colonist Errands: voice conversations with colonists through Gemini Live. I wanted text chat, a free choice of model provider, and colonists who live their own lives in chat. Since others might want the same, I decided to publish it.

Bug reports and ideas are welcome in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## Credits and references

This mod stands on other people's work:

- **[MineColonies](https://github.com/ldtteam/minecolonies)** by LDT Team — the colony simulator this is an addon for, along with [Structurize](https://github.com/ldtteam/Structurize), [BlockUI](https://github.com/ldtteam/BlockUI) and [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum).
- **[Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands)** by Lovkar (GPL-3.0-or-later) — the main code donor: errands through the colony's systems, reading citizen state, promises and relations with players. Ported files keep their original copyright notice and say where they came from.
- **[Talking Colonists](https://github.com/talking-colonists/talking-colonists)** by sshcrack ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/talking-colonists-minecolonies-addon)) — voice conversations with colonists through Gemini Live. Ideas borrowed from it: the budget for unprompted lines, rumors spreading through the colony, complaints that escalate. Its code is under the CoFH "Don't Be a Jerk" license, so nothing is copied verbatim; borrowed ideas link back to their source.
- **[ColonyChat](https://www.curseforge.com/minecraft/mc-mods/colonychat)** — an earlier take on LLM chat for MineColonies (Forge 1.20.1). Ideas and lessons learned only, no code.
- **[NeoForge](https://neoforged.net)** and its [ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle), which the project skeleton comes from.

## License

[GPL-3.0](LICENSE), as required by the Colonist Errands code it builds on.
