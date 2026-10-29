<p align="center"><img src=".github/logo.png" alt="Living Colonists logo" width="160"></p>

# MineColonies: Living Colonists

A [MineColonies](https://github.com/ldtteam/minecolonies) addon for NeoForge that lets colonists talk in text chat almost like other players. They start conversations, answer you, remember what you told them and gossip about the colony with each other. The words come from a language model behind any OpenAI-compatible API: DeepSeek, Gemini, OpenRouter, or a model running on your own machine.

> **Status: early development.** The project skeleton builds and loads next to MineColonies. Nothing below works yet; the feature list describes where it's going. Expect breaking changes until the first release.

## What it should feel like

You walk into your colony and see lines in chat (and, with [Comics Bubbles Chat](https://modrinth.com/mod/comics-bubbles-chat), speech bubbles over their heads):

```
<John Brown · Baker> flour for two more days. Is anyone actually working the fields?
<Clodagh Bennett · Farmer> John, look at yourself, the oven's been cold for three days
```

You walk up to the baker, press a key, and the chat input shows "→ John Brown". John stops and turns to you. You ask what the oven needs, and a second or two later he answers, going by what's actually in his storage and what he remembers about you. You ask him to bring coal to the warehouse: he takes it on through the colony's own request system and reports back later. Half an hour on, he messages you himself: "You promised firewood. Where's the firewood?"

## Planned features

- Colonists speak first, reply and talk among themselves, but only while a player is nearby, so an empty server spends no tokens.
- They know their situation from MineColonies itself: job, hunger, housing, happiness, time of day, weather, who is around, raids, construction, new citizens.
- Memory of recent lines and events, plus long-term facts like "Max promised to build me a house". It is stored with the world and goes into your backups.
- Mood sets the tone: a hungry colonist grumbles, a homeless one complains, one who just survived a raid is shaken.
- Errands go through the colony's own systems: fetching, delivering, crafting requests and more, based on [Colonist Errands](https://github.com/Lovkar-Squid/colonist-errands).
- You choose who you're talking to. Chat modes "Players", "Nearest colonist" (the one you're looking at, or the closest within ~8 blocks) and "Everyone nearby" (~16 blocks). A key cycles through them, an icon next to the chat input shows the current mode, and `/cm near`, `/cm all`, `/cm off` do the same.
- Speech bubbles via Comics Bubbles Chat when it's installed, plain chat otherwise.
- Any OpenAI-compatible provider, with the API key read from an environment variable.

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

No release yet. Once there is one, it goes into the `mods` folder of the server and of every client, like any other mod. Setting up the model provider and API key will be documented here.

## Building from source

You need JDK 21.

```bash
./gradlew build      # jar ends up in build/libs/
./gradlew runClient  # development client with MineColonies, single-player world
./gradlew runServer  # local dedicated server for network testing
```

## Roadmap

1. Skeleton: a command or chat prefix → a colonist → an AI reply in chat.
2. Perception and memory, ported from Colonist Errands.
3. Colonists speak first, with a line budget.
4. Colonists talk to each other.
5. Client side: speech bubbles, the addressing key and button.

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
