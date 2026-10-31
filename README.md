<p align="center"><img src=".github/logo.png" alt="Living Colonists logo" width="160"></p>

# Living Colonists for MineColonies

A [MineColonies](https://github.com/ldtteam/minecolonies) addon for NeoForge that lets colonists talk in text chat almost like other players. They start conversations, answer you, remember what you told them and gossip about the colony with each other. The words come from a language model behind any OpenAI-compatible API: DeepSeek, Gemini, OpenRouter, or a model running on your own machine.

> **Status: 0.3.2.** Everything below works and is being played on a live server. Expect rough edges, and please report them in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## An example

Colonists write in chat just like players do; hover over a name to see the profession and colony. You walk into your colony and see lines in chat and speech bubbles over their heads:

```
<John Brown> flour for two more days. Is anyone actually working the fields?
<Clodagh Bennett> John, look at yourself, the oven's been cold for three days
```

You look at the baker and press **U**: a line above the chat input shows "→ John Brown", and whatever you type goes to him. You ask what the oven needs, and a second or two later he answers from what's actually in his storage and what he remembers about you. You ask a courier to bring coal from the warehouse to the bakery: he asks you to confirm, walks to the warehouse and carries it over. Half an hour on, the baker messages you himself: "You promised firewood. Where's the firewood?"

## Features

- Colonists speak first, reply and talk among themselves, but only while a player is in the colony, so an empty server spends no tokens.
- They know their situation from MineColonies itself (job, home, hunger, health, happiness, what their workplace waits for, raids, weather, family and coworkers) and see what you hold and wear, and whether you look hurt.
- Look-ups and orders go through the colony's own systems, based on [Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands): stock, research, construction; build, craft, deliver, arm the guards and much more. Orders wait for your yes.
- Memory of recent lines, long-term facts like "Max promised to build me a house", promises with deadlines, and once a game week a short life story. It is stored with the world and goes into your backups.
- Every colonist has a personality, a mood for the day, a voice and a hope of their own; a hungry colonist grumbles, one who just survived a raid is shaken. Relations between colonists change by themselves: coworkers grow close, two contrary housemates get on each other's nerves.
- They notice things without being told: you coming back, a big deed, a gift in their bag, a new building site, a fight with raiders, a neighbour falling ill or getting a new job.
- Everything colonists say unprompted shares one budget per colony and per player, so a busy server stays quiet: a death or a raid always gets through, small talk gives way first.
- Speech bubbles over colonists' heads with typing dots, also with shaders. Each player can hide colonists' colony lines from their chat and keep only the bubbles.
- Any OpenAI-compatible provider, with separate models for chat and for writing characters, retries, a fallback endpoint and token statistics. Players' answers go first; when the provider keeps failing, background chatter pauses for a few minutes. An in-game settings screen, and `/lcdebug` for operators.
- Operators can turn the mod off for the whole server, a colony's owner for their own colony.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| MineColonies | 1.1.1405 or newer 1.1.x (checked: 1.1.1405, 1.1.1406) |
| Structurize | as your MineColonies needs (1.0.832 or newer) |
| BlockUI | as your MineColonies needs (1.0.199 or newer) |
| Domum Ornamentum | as your MineColonies needs (1.0.223 or newer) |

MineColonies changes between builds, so a much newer one may lose a feature now and then: the mod skips what it can't read instead of crashing. Please report it in Issues.

The mod is needed on the server and on every player's client; a client without it can't join, and the game says what is missing.

## Installation

Download the jar from [Releases](https://github.com/JerTheFox/minecolonies-living-colonists/releases), put it into the `mods` folder of the server and of every client, then set up the language model.

## Configuration

The model is set up on the server (in single-player, on your own machine). The easiest way is the settings screen: the *Config* button next to the mod in the mod list, or the *Living Colonists settings* key (unbound by default). It has every option below on four tabs (Connection, Behaviour, Limits, Usage), checks values before saving, tests the chat and character models and loads the provider's model list. Preset buttons for DeepSeek, OpenRouter and Ollama fill in the address and the models; you only enter the key. Changes apply at once. In a world, operators (and the owner of a single-player world) can change the server's settings and other players only look; from the main menu the screen edits the local file. API keys are never shown or sent to players: the screen only says whether one is set. Mods like Configured show the same options.

Or edit `config/livingcolonists-common.toml` by hand (it appears after the first launch): put your key into `apiKey` and adjust the rest of `[llm]`. The key is stored as plain text, so don't share the file; server owners can leave `apiKey` empty and set the environment variable `LIVINGCOLONISTS_API_KEY` instead.

| Option | Default | Meaning |
|---|---|---|
| `baseUrl` | `https://api.deepseek.com` | OpenAI-compatible API base URL |
| `chatModel` | `deepseek-flash` | model for replies, as the provider's `/models` lists it |
| `characterModel` | empty | optional smarter model for backgrounds, memory and life stories, e.g. `deepseek-v4-pro`; empty uses `chatModel` |
| `apiKey` | empty | your API key |
| `apiKeyEnv` | `LIVINGCOLONISTS_API_KEY` | environment variable read when `apiKey` is empty |
| `timeoutSeconds` | `30` | how long to wait for a reply |
| `retries` | `3` | retries after timeouts, lost connections, rate limits and server errors |
| `useTools` | `true` | let colonists look things up and take orders through function calling; turn off if your provider doesn't support tools |
| `maxTokens` | `2000` | token limit per request, including the model's thinking |
| `temperature` | `1.0` | higher is more varied |
| `extraParams` | `{}` | extra JSON merged into each request, e.g. `{"top_p": 0.9}`; better not to turn off the model's thinking: without it colonists make up facts |

`[llm.fallback]` is a second API for when the main one fails even after retries, for example a local model: `enabled`, `baseUrl`, `model`, `apiKey` (may stay empty for local models), `apiKeyEnv`.

The optional `[embeddings]` section lets colonists recall facts by meaning, in any language: "is your leg better?" finds "Ephraim's knee aches" though they share no word. Without it, facts are picked by the people in the talk, common words, importance and age. It takes an OpenAI-compatible `/embeddings` API: `baseUrl` (default OpenRouter, `https://openrouter.ai/api/v1`), `model` (default `baai/bge-m3`: multilingual, about $0.01 per million tokens, a fraction of a cent a day), `apiKey` and `apiKeyEnv` (default `LIVINGCOLONISTS_EMBEDDINGS_API_KEY`). It is on when a key is found or `baseUrl` is on this machine (for example Ollama with `bge-m3`). The model must know your players' languages; English-only models can't match Russian messages with English facts.

`[behaviour]`:

| Option | Default | Meaning |
|---|---|---|
| `enabled` | `true` | off: the mod does nothing on the server, no requests to the model, the chat works as without it |
| `profile` | `DEFAULT` | `MINIMAL` (the colony run by chat: plain answers, look-ups and orders, no character, memory or chatter; cheapest), `LIGHT`, `DEFAULT` or `IMMERSIVE` (colonists remember much more) |
| `personality`, `perception`, `memory`, `conversation`, `initiative` | `profile` | override one module: `off`, `low`, `default` or `high`; `conversation` sets how long colony chats run on their own, `initiative` how often colonists write to you first |
| `speechBubbles` | `true` | colonists' lines also as speech bubbles over their heads, with dots while they type |
| `playerBubbles` | `false` | speech bubbles and typing dots over players too |
| `bubbleRange` | `24` | how far away bubbles are seen, in blocks |
| `cooldownSeconds` | `3` | shortest time between one player's messages to colonists |
| `playerNames` | empty | what colonists call players instead of nicknames, e.g. `["Steve42=Steven,Steve,Stevie"]`, most common form first |
| `colonyChatAnywhere` | `false` | colony members read and write their colony's chat from anywhere, not only inside its borders |
| `membersOnly` | `true` | only the colony's owner, officers and friends may talk to its colonists; only owners and officers may give orders |
| `extraAdvancements` | empty | more advancements colonists hear about, as `id` or `id=what colonists say`, e.g. `["create:andesite_alloy=learned to make andesite alloy"]` |
| `logLevel` | `ALL` | how much the mod writes to the server log: `ALL`, `NO_CHAT` (without chat lines), `TECHNICAL` (start-up, settings changes, warnings and errors) or `OFF` (errors only) |

`[limits]` guards public servers against spam and runaway costs (both off by default, `0` is off): `messagesPerPlayerPerHour` and `tokensPerDay` (for the whole server; then colonists rest until midnight).

Each player also has one option of their own, on the Behaviour tab under the speech bubbles and in `config/livingcolonists-client.toml`: `colonyChatLines`. Off hides colonists' colony lines from your chat and leaves the bubbles; answers in the minute after you write to the colony, private talks and order questions stay in the chat.

### Personalities, memory and prompts

Every colonist gets an archetype (grumbler, chatterbox, quiet one, gossip, workhorse, idler, dreamer, optimist, worrier, joker, thinker), picked mostly from their MineColonies skills with a share of chance, and a short background the model writes once. Children get a temperament, sometimes take after a parent, and get an adult background when they grow up. Archetypes are JSON files in `config/livingcolonists/archetypes/`: `name`, `prompt` (how the colonist behaves), `weight` (how common), `talkativeness` and `contrariness` (0 to 1, how often they disagree) and `skills` (skill name to affinity, e.g. `{"Strength": 0.6, "Intelligence": -0.4}`). Add a file to add an archetype, delete one to remove it; delete the folder to get the defaults back.

The `perception` level sets how much of their situation colonists know. Unless it is `off`, they can also look things up while answering: another colonist, who fits a profession best by skills ("who would make a good builder?"), an overview of the colony. A colonist asked by name by another colonist may look things up too, and a look-up made in the last ten in-game minutes is known to the whole colony, so everyone quotes the same numbers.

Recent lines of each talk go back to the model as they are. Once a conversation goes quiet, the character model writes down what the colonists would remember a few days later: a promise, a plan, news, how someone felt. Trivia fades in a day or two, big news lasts much longer; the `memory` level sets how many facts a colonist keeps and for how long. Once a colonist has a few memories, and then once a game week, the model sums up their life in the colony in a few sentences, so they remember the colony's past after the facts fade. Now and then a colonist passes on as gossip what another player told them in private; what you asked to keep secret, they keep.

Promise a colonist planks by tomorrow or a house within a week, and they remember the deadline, remind you privately when it has passed, thank you when you keep it and hold it against you when you don't. A time you name ("in ten minutes", "by evening") is taken as the deadline by code, counted from when you said it. A promised home, job, treatment or building is noticed once it happens, and giving what you promised keeps the promise. Answering a colonist's request with "I will work on it!" counts as a promise too, due in a day. Colonists also remember whether you are kind or rude to them, and a player rude to several colonists gets a reputation.

Colonists talk like ordinary people: short lines, no forced cheer, and a mood for the day that the code rolls (calm, tired, irritable, distracted, bored, gloomy or good; unhappy colonists are in a bad mood more often). They remember who they get along with, so the same pairs side with each other or argue. Once a game day this changes by itself from real life: working in the same building brings two colonists closer, and sharing a home does too, unless both are contrary. When two become friends or fall out, both remember it with the reason. When something happens (a death, a birth, a child growing up, a newcomer, a finished building, a raid, someone falling ill or getting a new job) and players are around, a colonist brings it up and others join in.

Content files in `config/livingcolonists/content/`: `topics.txt` (subjects a colony chat turns to), `rumours.json` (advancements colonists hear about, the colony's milestones) and `handbook.json` (the mechanics handbook). A pack can add its own topics, advancements and articles.

Prompt templates in `config/livingcolonists/prompts/`: `reply-private.txt`, `reply-colony.txt`, `character.txt`, `background.txt`, `route-colony.txt`, `bio.txt`, `bio-child.txt`, `bio-grownup.txt`, `memory.txt`, `memory-player.txt`, `memory-colonists.txt`, `voice.txt`, `story.txt`. Placeholders like `{name}`, `{job}`, `{character}` and `{situation}` are filled in by the mod. Prompt and content files you haven't edited follow the mod's defaults when it updates; delete a file to restore its default. `/lcdebug reload` rereads them.

### Commands for operators

`/lcdebug` needs operator rights on a server, or cheats in single-player; regular players only have `/lc`. Without a name, colonist commands act on your conversation partner, or else on the colonist you look at.

| Command | What it does |
|---|---|
| `/lcdebug stats` | requests and tokens per model, this run and in total, and what colonists did since the server started: lines said and held back, events gone stale, repeats caught, look-ups |
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
| `/lcdebug reload` | reread archetypes, prompts and content files |

## Usage

Colonists listen while you are inside the colony's borders.

| Key | What it does |
|---|---|
| **T** | the usual chat with players (it also leaves colony mode or a private talk) |
| **Y** | write to the whole colony |
| **U** | talk privately to the colonist you look at |

- **The whole colony.** Name a colonist and they answer first; name a profession ("builders, when will you finish?") and those colonists answer; ask about food, the warehouse, buildings, research or defence and the profession that looks after it answers; a follow-up goes to whoever answered you last. Others join in only now and then, and wait a few seconds so you can answer first; after a look-up or an order the chat mostly stays quiet. When you fall silent, colonists talk among themselves, argue, answer each other or let it die down. Players inside the colony see it.
- **One colonist, privately.** Whatever you type goes to that colonist only, wherever in the colony you are, marked `<You> → <Colonist>` in chat. They keep working while you talk, and remember your recent talks. Clicking a colonist's name does the same. Press **U** while looking at nobody, or click ✕ above the chat input, to end it.

Colony members get a *Players | Colony* switch above the chat input. Unbound by default: a key to switch the mode and *Focus on colonist*, which picks a private partner without opening the chat. All keys can be rebound in Controls. Colonists take a moment to answer, as if reading and typing, and "Idris is typing..." shows above the hotbar. Names are shown in the colony's color. Colony and private lines go to the server log, like player chat.

Colonists also write to you first now and then, at uneven times: about a real problem of theirs (more pointed if it stays unsolved, until they give up asking), something you told them, news or small talk. They talk about their real day (worked, slept at home or outside, ate, hid from a raid), so "how was your day?" gets a true answer, and each hopes for one thing the colony can make come true, like their house a level up or a tavern; they mention it now and then and tell you gladly once it happens. A line that only repeats what was said lately is caught and asked for again. Asked how the colony works ("will you take food from a chest?"), they answer from a handbook of MineColonies' mechanics, with buildings and items named in the players' language.

Look-ups and orders: ask "how much iron do we have?" or "Idris, upgrade the bakery". Colonists know the stock, the food, what is being built, the research, who is unhappy, who died, whether the guards are armed, what the colony needs from you and where someone or something is. They take a job, order a craft, build or upgrade a building and move it up the builders' queue, arm the guards, plant a field, start a research, add a dish to the restaurant's menu, sort the warehouse, move into another home, tell you when they find something, pass a message to another player and remind you of something later. They come over, follow you, wait, go somewhere, gather, go home or lead you the way; couriers fetch from the warehouse or deliver to a building; guards escort you, hold your spot, go on alert, patrol, stand down and count their kills. A builder knows his construction as the resource scroll shows it. Look-ups answer at once. Orders come only from the owner and officers, only from your own words, and wait for your yes: *Yes* and *No* buttons, or a short "yes" or "no" in chat.

`/lc` commands for everyone: `/lc yes` and `/lc no` answer an order question, `/lc undo` takes your last order back where it can, `/lc lock` starts a private talk with the colonist you look at, `/lc help` sums up the keys and what you can ask, and `/lc colony off` / `/lc colony on` lets a colony's owner silence their colonists.

## Building from source

You need JDK 21. For the dev runs, copy `.env.example` to `.env` and put your API key there; `runClient` and `runServer` pass it to the game.

```bash
./gradlew build      # jar ends up in build/libs/
./gradlew runClient  # development client with MineColonies, single-player world
./gradlew test       # unit tests
./gradlew runServer  # local dedicated server for network testing
```

## Translations

The mod follows the game's language; English and Russian are included. To add a language, copy [`en_us.json`](src/main/resources/assets/livingcolonists/lang/en_us.json) to your locale code (for example `de_de.json`) in the same folder, translate the values and open a pull request. Colonists answer in the language you write in, whatever the client language is.

## Roadmap

1. ✅ A command → a colonist → an AI reply in chat.
2. ✅ Talking without commands: the whole colony or one colonist privately.
3. ✅ Personalities, perception and memory: archetypes, backgrounds, memory, promises and relations, `/lcdebug`.
4. ✅ In-game settings screen.
5. ✅ Errands through the colony's systems: look-ups, orders confirmed with Yes / No, walks, couriers, guards, mail and reminders.
6. ✅ Colonists speak first, at uneven times and taking turns.
7. ✅ Colonists talk to each other.
8. ✅ Speech bubbles.

Since then: memory by player account and by meaning, colonists' real day, goals and voices, the mechanics handbook, names in the players' language, colonists noticing players come and go, rumours of big deeds, one budget for unprompted lines, gifts, deadlines from your own words, provider presets, life stories, shared look-ups, relations that drift, bubbles-only chat, on/off switches.

Next: playing 0.3.2 on a live server and fixing what comes up; then a longer memory for long-running servers and small requests from colonists with a reward.

## About this project

This is my project. I'm a sysadmin, not a Java developer: I decide what the mod does and how colonists behave, review and approve each change, fix things by hand when needed and play every build on our server before it's released. Claude, writes most of the code.

It started as a private mod for my home server. We had been running Talking Colonists with Colonist Errands: voice conversations with colonists through Gemini Live. I wanted text chat, a free choice of model provider, and colonists who live their own lives in chat. Since others might want the same, I decided to publish it.

Bug reports and ideas are welcome in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## Credits and references

- **[MineColonies](https://github.com/ldtteam/minecolonies)** by LDT Team — the colony simulator this is an addon for, along with [Structurize](https://github.com/ldtteam/Structurize), [BlockUI](https://github.com/ldtteam/BlockUI) and [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum).
- **[Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands)** by Lovkar (GPL-3.0-or-later) — the main code donor: errands through the colony's systems, reading citizen state, promises and relations with players. Ported files keep their original copyright notice and say where they came from.
- **[Talking Colonists](https://github.com/talking-colonists/talking-colonists)** by sshcrack ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/talking-colonists-minecolonies-addon)) — voice conversations with colonists through Gemini Live. Ideas borrowed from it: the budget for unprompted lines, rumors spreading through the colony, complaints that escalate. Its code is under the CoFH "Don't Be a Jerk" license, so nothing is copied verbatim; borrowed ideas link back to their source.
- **[ColonyChat](https://www.curseforge.com/minecraft/mc-mods/colonychat)** — an earlier take on LLM chat for MineColonies (Forge 1.20.1). Ideas and lessons learned only, no code.
- **[NeoForge](https://neoforged.net)** and its [ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle), which the project skeleton comes from.

## License

[GPL-3.0](LICENSE), as required by the Colonist Errands code it builds on.
