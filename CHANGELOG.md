# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Profiles `MINIMAL`, `LIGHT`, `DEFAULT`, `IMMERSIVE` set how deep personalities, perception and memory go; each module can be overridden separately. `/lcdebug profile` shows or changes the profile.
- Only a colony's owner, officers and friends can talk to its colonists (`membersOnly`, on by default).
- A short cooldown between one player's messages to colonists (`cooldownSeconds`, 3 by default).
- Separate `chatModel` and optional `characterModel` (for backgrounds and memory summaries; empty uses the chat model).
- Retries after temporary failures (timeouts, rate limits, server errors) and an optional fallback API, for example a local model.
- Token usage statistics per model, for this run and in total, saved with the world: `/lcdebug stats` (operators).
- Two chat modes, switched with **H** or with the *Players | Colony* switch above the chat input. In *Colony* mode, while you are inside the colony's borders, your ordinary chat goes to the whole colony: a colonist you name answers, a profession you name ("builders, when will you finish?") gets answers from those colonists, and otherwise two or three random colonists answer. The model works out who is addressed, in any language. Players inside the colony see the conversation.
- Private talks. **Y** ("Talk to colonist") picks the colonist you look at and opens the chat; your messages then go to that colonist only, like a direct message, from anywhere in the colony. The colonist keeps working. Press **Y** while looking at nobody, click the ✕ above the chat input, or leave the colony to end it. Clicking a colonist's name in chat starts a private talk too.
- Colonists answer even when they are in unloaded chunks of the colony.
- Colonists take a moment before answering, as if typing, and several answers in a colony come a couple of seconds apart.
- `apiKey` in the config, for players who don't want to deal with environment variables.
- `/lc lock` and `/lc mode [players|colony]` as fallbacks for clients without the mod.
- English and Russian translations.
- `/lc talk <message>`: talk to the colonist you look at, or the nearest one within 8 blocks. The reply comes from the language model and appears in chat like a player message; hovering the name shows the profession and colony.
- Project skeleton from the NeoForge 1.21.1 ModDevGradle MDK: mod id `livingcolonists`, `runClient` and `runServer` with MineColonies 1.1.1405 and its dependencies.
- Exact version pins for Minecraft 1.21.1, NeoForge 21.1.264 and MineColonies 1.1.1405.
- Mod logo in the mod list.
- Common config `livingcolonists-common.toml` for the OpenAI-compatible API: base URL, model, API key or its environment variable, timeout, max tokens, temperature and extra request parameters. Defaults point to DeepSeek (`deepseek-flash`).
