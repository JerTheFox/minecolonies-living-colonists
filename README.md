<p align="center"><img src=".github/logo.png" alt="Living Colonists logo" width="160"></p>

# Living Colonists for MineColonies

A [MineColonies](https://github.com/ldtteam/minecolonies) addon for NeoForge that lets colonists talk in text chat almost like other players. They start conversations, answer you, remember what you told them and gossip about the colony with each other. The words come from a language model behind any OpenAI-compatible API: DeepSeek, Gemini, OpenRouter, or a model running on your own machine.

> **Status: 0.6.0, early development.** Everything below works and is played on our home server, but the insides are still being reworked, and settings, prompt files and whole features can change between versions. Read the [changelog](CHANGELOG.md) before updating, keep a backup of your world, and please report problems in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

> **You need a language model.** The mod doesn't include one. Colonists only talk once the server can reach an OpenAI-compatible API with your own key (paid by use) or a local model, for example in Ollama. Without it, players who write to colonists only get a note that no API key is set.

## An example

You walk into your colony and see lines in chat and speech bubbles over their heads:

```
<John Brown> flour for two more days. Is anyone actually working the fields?
<Clodagh Bennett> John, look at yourself, the oven's been cold for three days
```

You look at the baker and press **U**: whatever you type now goes to him. You ask what the oven needs, and he answers from what's actually in his storage and what he remembers about you. You ask a courier to bring coal to the bakery: he asks you to confirm, walks to the warehouse and carries it over. Half an hour later the baker messages you himself: "You promised firewood. Where's the firewood?"

## Features

- Colonists speak first, reply and talk among themselves, but only while a player is in the colony, so an empty server spends no tokens.
- They know their situation from MineColonies (job, home, hunger, health, happiness, what their workplace waits for, raids, weather, family, coworkers) and see what you hold and wear.
- Look-ups and orders go through the colony's own systems, based on [Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands): stock, research, construction; build, craft, deliver, arm the guards and more. Orders wait for your yes.
- Memory of recent lines, long-term facts, promises with deadlines, and a short life story once a game week. Stored with the world.
- Personalities, a mood for the day, a voice and a hope of their own. Relations between colonists drift by themselves.
- The colony has a mood (panic in a raid, mourning, joy at a birth, gloom when people go hungry) that colours what everyone says.
- Talks take shapes: mostly ordinary chat, sometimes teasing, gossip or an argument, running stories between colonists, a rare catchphrase. Plain words, like people typing in a game chat.
- Friendship in the spirit of Stardew Valley: gift tastes by job and character with an odd favourite each, friendship levels with a word at every new one, birthdays, and a colony calendar with eight holidays that only moves while someone plays in the colony.
- Friends write now and then and leave a small gift in the notebook. When you're badly hurt in the colony, a friend nearby helps by their job: a healer heals, a cook feeds you, a guard goes for the attacker, a builder puts up a wall. Colonists who like you walk faster and learn faster; one who holds a grudge drags their feet.
- How you treat colonists shows in their MineColonies happiness.
- The Colony Notebook: promises, letters with gifts, relations and known gift tastes, orders with undo, the colony's date and mood, settings and help.
- Resurrected colonists remember dying; mourners know who they mourn.
- One budget for everything colonists say unprompted, so a busy server stays readable: a death or a raid always gets through, small talk gives way.
- Speech bubbles with typing dots, also with shaders; bubbles-only chat for those who want it.
- Any OpenAI-compatible provider, separate models for chat and for writing characters, retries, a fallback endpoint, token statistics, an in-game settings screen and `/lcdebug` for operators.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| MineColonies | 1.1.1405 or newer 1.1.x (checked: 1.1.1405 to 1.1.1407) |
| Structurize | as your MineColonies needs (1.0.832 or newer) |
| BlockUI | as your MineColonies needs (1.0.199 or newer) |
| Domum Ornamentum | as your MineColonies needs (1.0.223 or newer) |

A much newer MineColonies may lose a feature now and then: the mod skips what it can't read instead of crashing. Please report it in Issues.

The mod is needed on the server and on every client, same version on both.

## Installation

Download the jar from [Releases](https://github.com/JerTheFox/minecolonies-living-colonists/releases), put it into the `mods` folder of the server and of every client, then set up the language model.

## Configuration

The model is set up on the server (in single-player, on your own machine). The easiest way is the settings screen: the *Config* button in the mod list, the *Living Colonists settings* key (unbound by default), or *All settings* in the Colony Notebook. Presets for DeepSeek, OpenRouter and Ollama fill in the address and models, you only enter the key. Changes apply at once. Operators can change the server's settings, other players only look. API keys are never shown or sent to players.

Or edit `config/livingcolonists-common.toml` by hand (it appears after the first launch). The key is stored as plain text; server owners can leave `apiKey` empty and set `LIVINGCOLONISTS_API_KEY` instead.

| Option | Default | Meaning |
|---|---|---|
| `baseUrl` | `https://api.deepseek.com` | OpenAI-compatible API base URL |
| `chatModel` | `deepseek-flash` | model for replies, as the provider's `/models` lists it |
| `characterModel` | empty | optional smarter model for backgrounds, memory and life stories; empty uses `chatModel` |
| `apiKey` | empty | your API key |
| `apiKeyEnv` | `LIVINGCOLONISTS_API_KEY` | environment variable read when `apiKey` is empty |
| `timeoutSeconds` | `30` | how long to wait for a reply |
| `retries` | `3` | retries after timeouts, lost connections, rate limits and server errors |
| `useTools` | `true` | look-ups and orders through function calling; turn off if your provider doesn't support tools |
| `maxTokens` | `2000` | token limit per request, including the model's thinking |
| `temperature` | `1.0` | higher is more varied |
| `extraParams` | `{}` | extra JSON for each request, e.g. `{"top_p": 0.9}`; better not to turn off thinking: without it colonists make things up |

`[llm.fallback]` is a second API for when the main one keeps failing: `enabled`, `baseUrl`, `model`, `apiKey`, `apiKeyEnv`.

`[embeddings]` (optional) lets colonists recall facts by meaning, in any language: "is your leg better?" finds "Ephraim's knee aches". It takes an OpenAI-compatible `/embeddings` API: `baseUrl` (default OpenRouter), `model` (default `baai/bge-m3`, about a cent per million tokens), `apiKey`, `apiKeyEnv` (default `LIVINGCOLONISTS_EMBEDDINGS_API_KEY`). The model must know your players' languages.

`[behaviour]`:

| Option | Default | Meaning |
|---|---|---|
| `enabled` | `true` | off: the mod does nothing on the server |
| `profile` | `DEFAULT` | `MINIMAL` (the colony run by chat, cheapest), `LIGHT`, `DEFAULT` or `IMMERSIVE` (colonists remember much more) |
| `personality`, `perception`, `memory`, `conversation`, `initiative` | `profile` | override one module: `off`, `low`, `default` or `high` |
| `speechBubbles` | `true` | colonists' lines as speech bubbles too |
| `playerBubbles` | `false` | bubbles and typing dots over players too (off in case you use another bubble mod) |
| `bubbleRange` | `24` | how far bubbles are seen, in blocks |
| `cooldownSeconds` | `3` | shortest time between one player's messages to colonists |
| `playerNames` | empty | what colonists call players, e.g. `["Steve42=Steven,Steve,Stevie"]` |
| `colonyChatAnywhere` | `false` | members use their colony's chat from anywhere |
| `membersOnly` | `true` | only owner, officers and friends talk to colonists; only owner and officers give orders |
| `extraAdvancements` | empty | more advancements colonists hear about, `id` or `id=what colonists say` |
| `logLevel` | `ALL` | `ALL`, `NO_CHAT`, `TECHNICAL` or `OFF` |

`[limits]` (both off by default): `messagesPerPlayerPerHour` and `tokensPerDay` for the whole server.

`[friendship]`: `letters` and `helpInTrouble` (both on), `friendshipWalkPercent` (25) and `friendshipXpPercent` (50), how much faster a best friend walks and learns, and how much slower one with a grudge; 0 turns it off.

Each player also has `colonyChatLines` in `config/livingcolonists-client.toml` (or the Settings tab of the notebook): off keeps colony talk as bubbles only.

### Personalities, memory and content files

Every colonist gets an archetype (grumbler, chatterbox, quiet one, gossip, workhorse, idler, dreamer, optimist, worrier, joker, thinker), picked mostly from their skills, and a short background written once. Archetypes are JSON files in `config/livingcolonists/archetypes/`: `name`, `prompt`, `weight`, `talkativeness`, `contrariness` and `skills`. Add a file to add one, delete the folder to get the defaults back.

Once a conversation goes quiet, the character model writes down what the colonists would remember: a promise, a plan, news, how someone felt. Trivia fades in a day or two, big news lasts longer. Promises have deadlines taken from your own words, and colonists notice when a promised home, job, cure or building happens. Answering a colonist's request with "I will work on it!" counts as a promise too.

What colonists look up is shared with the colony for ten minutes, with its age, so they don't contradict each other.

Content files in `config/livingcolonists/content/`: `topics.txt`, `talk.json` (talk shapes), `moods.json` (what stirs the colony's mood), `catchphrases.json`, `gifts.json` (tastes), `calendar.json` (year, holidays), `friendship.json` (levels, cooling, what is said at each), `letters.json` (how often friends write, gifts by job), `help.json` (help in trouble by job), `rumours.json` and `handbook.json`. Prompt templates are in `config/livingcolonists/prompts/`. Files you haven't edited follow the mod's defaults on update; delete one to restore it. `/lcdebug reload` rereads them.

### Commands for operators

`/lcdebug` needs operator rights, or cheats in single-player. Without a name, colonist commands act on your conversation partner or the colonist you look at.

| Command | What it does |
|---|---|
| `/lcdebug stats` | requests and tokens per model, and what colonists did since start |
| `/lcdebug profile [name]` | show or change the profile |
| `/lcdebug colonist [name]` | archetype, background, skills and what the colonist knows |
| `/lcdebug archetype <id\|reroll> [name]` | set or re-pick an archetype |
| `/lcdebug bio regenerate [name]` | write a new background |
| `/lcdebug prompt [name]` | the last prompt for this colonist's reply |
| `/lcdebug memory show\|clear [name]` | a colonist's facts and private talks |
| `/lcdebug memory digest` | write down every waiting conversation now |
| `/lcdebug bonds show\|clear [name]` | promises and how each player treats them |
| `/lcdebug relations [name\|all]` | how colonists get along |
| `/lcdebug mood` | the colony's mood and what stirred it |
| `/lcdebug calendar [set <day>]` | the colony calendar, or jump to a day |
| `/lcdebug thread [break]` | the colony chat thread; `break` stops it |
| `/lcdebug orders` | the latest orders |
| `/lcdebug reload` | reread archetypes, prompts and content files |

## Usage

Colonists listen while you are inside the colony's borders.

| Key | What it does |
|---|---|
| **T** | the usual chat with players |
| **Y** | write to the whole colony |
| **U** | talk privately to the colonist you look at |

In the colony chat, name a colonist and they answer first; name a profession ("builders, when will you finish?") and those answer; ask about food or buildings and the one who looks after it answers. When you go quiet, colonists talk among themselves. A private talk goes only to that colonist, wherever in the colony you are; press **U** at nobody or click ✕ to end it. Colony members get a *Players | Colony* switch above the chat input.

Ask "how much iron do we have?" or "Idris, upgrade the bakery". Look-ups answer at once. Orders come only from the owner and officers, only in your own words, and wait for your yes (buttons, or "yes"/"no" in chat).

Put something into a colonist's inventory through their inventory window, and it's a gift. Gifts from colonists wait in the notebook's Letters tab until you take them. A calendar day passes when you spend five minutes in the colony that game day. The notebook shows what's new with a small mark on its tabs.

`/lc` for everyone: `yes`, `no`, `undo`, `lock`, `help`, `promises`, and `colony off` / `colony on` for a colony's owner.

## Building from source

You need JDK 21. For dev runs, copy `.env.example` to `.env` and put your API key there.

```bash
./gradlew build      # jar ends up in build/libs/
./gradlew runClient  # development client with MineColonies
./gradlew test       # unit and scenario tests, the model is faked
./gradlew runServer  # local dedicated server
```

## Translations

The mod follows the game's language; English and Russian are included. To add one, copy [`en_us.json`](src/main/resources/assets/livingcolonists/lang/en_us.json) to your locale code, translate the values and open a pull request. Colonists answer in the language you write in, whatever the client language is.

## Roadmap

Done: talking without commands, personalities, memory, promises and relations, the settings screen, errands, colonists speaking first and to each other, speech bubbles, the colony's mood, the Colony Notebook, friendship in the spirit of Stardew Valley (gift tastes, levels, the calendar with birthdays and holidays, letters, help in trouble, friendship in their work).

Next: small quests from colonists with a reward.

## About this project

This is my project. I'm a sysadmin, not a Java developer: I decide what the mod does and how colonists behave, review and approve each change, fix things by hand when needed and play every build on our server before it's released. Claude writes most of the code.

It started as a private mod for my home server. We had been running Talking Colonists with Colonist Errands: voice conversations with colonists through Gemini Live. I wanted text chat, a free choice of model provider, and colonists who live their own lives in chat. Since others might want the same, I decided to publish it.

My wife is the one who got me into this. She supports it, tests it with me on our server, helps with ideas and drew the Colony Notebook's icon by hand.

Bug reports and ideas are welcome in [Issues](https://github.com/JerTheFox/minecolonies-living-colonists/issues).

## Credits and references

- **[MineColonies](https://github.com/ldtteam/minecolonies)** by LDT Team — the colony simulator this is an addon for, along with [Structurize](https://github.com/ldtteam/Structurize), [BlockUI](https://github.com/ldtteam/BlockUI) and [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum). The Colony Notebook is drawn on MineColonies' town hall book, window tabs and icons, loaded from MineColonies itself; none of its art is copied into this mod.
- **[Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands)** by Lovkar (GPL-3.0-or-later) — the main code donor: errands through the colony's systems, reading citizen state, promises and relations with players. Ported files keep their original copyright notice and say where they came from.
- **[Talking Colonists](https://github.com/talking-colonists/talking-colonists)** by sshcrack ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/talking-colonists-minecolonies-addon)) — voice conversations with colonists through Gemini Live. Ideas borrowed from it: the budget for unprompted lines, rumors spreading through the colony, complaints that escalate. Its code is under the CoFH "Don't Be a Jerk" license, so nothing is copied verbatim; borrowed ideas link back to their source.
- **[ColonyChat](https://www.curseforge.com/minecraft/mc-mods/colonychat)** — an earlier take on LLM chat for MineColonies (Forge 1.20.1). Ideas and lessons learned only, no code.
- **[Stardew Valley](https://www.stardewvalley.net)** by ConcernedApe — the idea behind gift tastes, friendship levels, birthdays and holidays. Ideas only.
- **[NeoForge](https://neoforged.net)** and its [ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle), which the project skeleton comes from.

## License

[GPL-3.0](LICENSE), as required by the Colonist Errands code it builds on.
