<p align="center"><img src=".github/logo.png" alt="Living Colonists logo" width="160"></p>

# MineColonies: Living Colonists

A [MineColonies](https://github.com/ldtteam/minecolonies) addon for NeoForge that lets colonists talk in text chat almost like other players. They start conversations, answer you, remember what you told them and gossip about the colony with each other. The words come from a language model behind any OpenAI-compatible API: DeepSeek, Gemini, OpenRouter, or a model running on your own machine.

> **Status: early development.** You can already talk to colonists (see [Usage](#usage)); the rest of this page describes where the mod is going. Expect breaking changes until the first release.

## What it should feel like

Colonists write in chat just like players do; hover over a name to see the profession and colony. You walk into your colony and see lines in chat (and, with [Comics Bubbles Chat](https://modrinth.com/mod/comics-bubbles-chat), speech bubbles over their heads):

```
<John Brown> flour for two more days. Is anyone actually working the fields?
<Clodagh Bennett> John, look at yourself, the oven's been cold for three days
```

You look at the baker and press a key: the chat opens, and a line above the input shows "→ John Brown". From now on whatever you type goes to him. You ask what the oven needs, and a second or two later he answers, going by what's actually in his storage and what he remembers about you. You ask him to bring coal to the warehouse: he takes it on through the colony's own request system and reports back later. Half an hour on, he messages you himself: "You promised firewood. Where's the firewood?"

## Planned features

- Colonists speak first, reply and talk among themselves, but only while a player is nearby, so an empty server spends no tokens.
- They know their situation from MineColonies itself: job, hunger, housing, happiness, time of day, weather, who is around, raids, construction, new citizens.
- Memory of recent lines and events, plus long-term facts like "Max promised to build me a house". It is stored with the world and goes into your backups.
- Mood sets the tone: a hungry colonist grumbles, a homeless one complains, one who just survived a raid is shaken.
- Errands go through the colony's own systems: fetching, delivering, crafting requests and more, based on [Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands).
- Talk to the whole colony or to one colonist privately; colonists understand names and professions in any language.
- Speech bubbles via Comics Bubbles Chat when it's installed, plain chat otherwise.
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
| Comics Bubbles Chat | 1.4.0, optional |

The mod is needed on both the server and the client.

## Installation

No release yet. Once there is one, it goes into the `mods` folder of the server and of every client, like any other mod.

## Configuration

The language model is set up on the server (in single-player, on your own machine):

Open `config/livingcolonists-common.toml` (it appears after the first launch), put your provider's key into `apiKey` and adjust the rest of the `[llm]` section if needed. The key is stored there as plain text, so don't share the file. Server owners who prefer not to keep it in a file can leave `apiKey` empty and set the environment variable `LIVINGCOLONISTS_API_KEY` instead.

| Option | Default | Meaning |
|---|---|---|
| `baseUrl` | `https://api.deepseek.com` | OpenAI-compatible API base URL |
| `chatModel` | `deepseek-flash` | model for replies, as the provider's `/models` lists it |
| `characterModel` | empty | optional smarter model for backgrounds and memory summaries, e.g. `deepseek-v4-pro`; empty uses `chatModel` |
| `apiKey` | empty | your API key |
| `apiKeyEnv` | `LIVINGCOLONISTS_API_KEY` | environment variable read when `apiKey` is empty |
| `timeoutSeconds` | `30` | how long to wait for a reply |
| `retries` | `3` | retries after timeouts, lost connections, rate limits and server errors |
| `useTools` | `true` | let colonists look up colony data through function calling; turn off if your provider doesn't support tools |
| `maxTokens` | `2000` | token limit per request, including the model's thinking |
| `temperature` | `1.0` | higher is more varied |
| `extraParams` | `{}` | extra JSON merged into each request, e.g. `{"thinking":{"type":"disabled"}}` for DeepSeek |

The `[llm.fallback]` section sets up a second API used when the main one fails even after retries, for example a local model: `enabled`, `baseUrl`, `model`, `apiKey` (may stay empty for local models), `apiKeyEnv`.

The `[behaviour]` section sets how colonists behave:

| Option | Default | Meaning |
|---|---|---|
| `profile` | `DEFAULT` | `MINIMAL` (orders and answers only, cheapest), `LIGHT`, `DEFAULT` or `IMMERSIVE` (colonists remember much more) |
| `personality`, `perception`, `memory`, `conversation` | `profile` | override one module: `off`, `low`, `default` or `high`; `conversation` sets how long colony chats run on their own |
| `cooldownSeconds` | `3` | shortest time between one player's messages to colonists |
| `playerNames` | empty | what colonists call players instead of nicknames, e.g. `["JerTheFox=Максим,Макс,Максик"]`, most common form first |
| `colonyChatAnywhere` | `false` | colony members read and write their colony's chat from anywhere, not only inside its borders |
| `membersOnly` | `true` | only the colony's owner, officers and friends may talk to its colonists; only owners and officers may give orders |

The optional `[limits]` section guards public servers against chat spam and runaway costs; `0` turns a limit off, and both are off by default: `messagesPerPlayerPerHour` (messages one player may send to colonists in an hour) and `tokensPerDay` (tokens the whole server may spend in a day; then colonists rest until midnight).

### Personalities and prompts

Every colonist gets an archetype (grumbler, chatterbox, quiet one, gossip, workhorse, idler, dreamer, optimist, worrier, joker, thinker), picked mostly from their MineColonies skills with a share of chance, and a short background the model writes once. Children get a description of their temperament, sometimes take after a parent, and get an adult background when they grow up.

Archetypes live in `config/livingcolonists/archetypes/` as JSON files: `name`, `prompt` (how the colonist behaves), `weight` (how common), `talkativeness` (0 to 1) and `contrariness` (0 to 1, how often they disagree) and `skills` (skill name to affinity, e.g. `{"Strength": 0.6, "Intelligence": -0.4}`). Add a file to add an archetype, delete one to remove it; delete the folder to get the defaults back.

Colonists also know their situation from MineColonies: their job and what work they can't do, home, hunger and food, health and sickness, happiness and its causes, what their workplace waits for, the weather, the colony, a raid, and how their family and coworkers are doing. The `perception` level sets how much of it they know. From the `default` level on, colonists can also look things up while answering: facts about another colonist, who fits a profession best by its skills, and an overview of the colony. Ask "who would make a good builder?" and the answer comes from the real skills, not a guess.

Colonists remember. Recent lines of each private talk and of the colony chat go back to the model as they are. Once a conversation goes quiet, the character model writes down what the colonists who took part would remember a few days later: a promise, a plan, news, how someone felt. Every fact gets an importance; trivia fades in a day or two, big news lasts much longer. The `memory` level sets how many facts a colonist keeps and for how long. Now and then a colonist passes on, as gossip, something another player told them in private; what you asked to keep secret, they keep.

Promises count. Promise a colonist planks by tomorrow or a house within a week, and they remember the deadline, remind you when it has passed, thank you when you keep it and hold it against you when you don't. A promised home, job, treatment or building is noticed by itself once it happens. Colonists also remember whether you are kind or rude to them, and the colony talks: a player rude to several colonists gets a reputation.

Colonists have their own life too. They remember who they get along with and who gets on their nerves, so the same pairs side with each other or argue. When something happens in the colony (a death, a birth, a child growing up, a newcomer, a finished building, a raid) and players are around, a colonist brings it up in the colony chat and others join in. A promise past its deadline gets you a private reminder from the colonist waiting for it.

Prompt templates live in `config/livingcolonists/prompts/`: `reply-private.txt`, `reply-colony.txt`, `character.txt`, `background.txt`, `route-colony.txt`, `bio.txt`, `bio-child.txt`, `bio-grownup.txt`, `memory.txt`, `memory-player.txt`, `memory-colonists.txt`. Placeholders like `{name}`, `{job}`, `{character}` and `{situation}` are filled in by the mod. Files you haven't edited follow the mod's defaults when it updates; delete a file to restore its default. `/lcdebug reload` rereads both folders.

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
| `/lcdebug memory digest` | write down every conversation waiting to be noted, right away |
| `/lcdebug thread [break]` | the colony chat thread and its recent lines; `break` stops it |
| `/lcdebug reload` | reread archetypes and prompts |

## Usage

Colonists listen while you are inside the colony's borders. Three keys cover the chat:

| Key | What it does |
|---|---|
| **T** | the usual chat with players (it also leaves colony mode or a private talk) |
| **Y** | write to the whole colony |
| **U** | talk privately to the colonist you look at |

- **The whole colony.** Name a colonist and they answer first; name a profession ("builders, when will you finish?") and those colonists answer. Then the conversation lives on its own: others may join in, argue, answer each other or let it die down, and it can drift to another topic. Players inside the colony see it.
- **One colonist, privately.** Whatever you type goes to that colonist only, like a direct message, wherever in the colony you are; such lines are marked `<You> → <Colonist>` in chat. They keep working while you talk, and remember your recent talks. Clicking a colonist's name in chat does the same. Press **U** while looking at nobody, or click ✕ above the chat input, to end it.

For colony members (owner, officers, friends), a *Players | Colony* switch above the chat input shows where your message goes and can be clicked. Unbound by default: a key to switch the mode, and *Focus on colonist*, which picks a private partner without opening the chat. All keys can be rebound in Controls. Colonists take a moment to answer, as if reading and typing: after the reading pause, "Idris is typing..." shows above the hotbar (or next to the switch while the chat is open). Colonists' names are shown in their colony's color, and so are the arrow and addressee of private lines. Colony and private lines are written to the server log, like player chat.

Commands do the same for clients without the mod: `/lc talk <message>`, `/lc lock`, `/lc mode [players|colony]`.

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
3. Personalities, perception and memory: archetypes, backgrounds, memory profiles, `/lcdebug`.
4. In-game settings screen.
5. Errands through the colony's request system.
6. Colonists speak first, with a line budget.
7. Colonists talk to each other.
8. Speech bubbles.

## About this project

I'm not a programmer. This mod is written with the help of [Claude](https://claude.ai), Anthropic's AI assistant: I set the direction, test in game and make the decisions, and Claude writes most of the code.

It started as a private mod for my home server. We had been running Talking Colonists with Colonist Errands: voice conversations with colonists through Gemini Live. I wanted text chat, a free choice of model provider, and colonists who live their own lives in chat. Since others might want the same, I decided to publish it.

Bug reports and ideas are welcome in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## Credits and references

This mod stands on other people's work:

- **[MineColonies](https://github.com/ldtteam/minecolonies)** by LDT Team — the colony simulator this is an addon for, along with [Structurize](https://github.com/ldtteam/Structurize), [BlockUI](https://github.com/ldtteam/BlockUI) and [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum).
- **[Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands)** by Lovkar (GPL-3.0-or-later) — the main code donor: errands through the colony's systems, reading citizen state, promises and relations with players. Ported files keep their original copyright notice and say where they came from.
- **[Talking Colonists](https://github.com/talking-colonists/talking-colonists)** by sshcrack ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/talking-colonists-minecolonies-addon)) — voice conversations with colonists through Gemini Live. Ideas borrowed from it: a colonist stopping and turning to you when you talk, the budget for unprompted lines, rumors spreading through the colony, complaints that escalate. Its code is under the CoFH "Don't Be a Jerk" license, so nothing is copied verbatim; borrowed ideas link back to their source.
- **[ColonyChat](https://www.curseforge.com/minecraft/mc-mods/colonychat)** — an earlier take on LLM chat for MineColonies (Forge 1.20.1). Ideas and lessons learned only, no code.
- **[Comics Bubbles Chat](https://github.com/Thomas7520/ComicsBubblesChat)** by Thomas7520 ([Modrinth](https://modrinth.com/mod/comics-bubbles-chat)) — planned optional integration for speech bubbles. None of its code or assets are included.
- **[NeoForge](https://neoforged.net)** and its [ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle), which the project skeleton comes from.

## License

[GPL-3.0](LICENSE), as required by the Colonist Errands code it builds on.
