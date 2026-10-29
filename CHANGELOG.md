# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Colonists can look up colony data while answering, through function calling: facts about another colonist, who fits a profession best by its primary and secondary skill, and an overview of the colony. `useTools` turns it off for providers without tool support. `/lcdebug prompt` shows the calls.
- Colonists describe skills, moods and amounts in words instead of quoting numbers, unless asked how many.
- `playerNames` in the config: what colonists call players instead of their nicknames, e.g. `"JerTheFox=Максим,Макс,Максик"` (most common first). Colonists use the name only now and then, not in every reply.
- Builders know what they are building and which materials are still missing.
- Every worker knows their workplace level and the orders it is making for others (crafters, the warehouse, couriers); farmers know their fields, miners their mine levels, couriers their queue, healers the patients, researchers the research in progress.
- Colonists answer what they are asked instead of reciting their worries: only about a quarter of replies may mention one worry unasked, and only about a third may use the player's name (the mod decides per reply, since a model can't keep "now and then").
- Colonists tell apart deliveries that are on the way from things nobody in the colony can provide and a player has to bring.
- Perception: colonists know their job and which work they can't do (and which workplaces the colony has), their home, hunger and where to get food, health and sickness, happiness and its causes, what their workplace is waiting for, the weather, the colony and a raid, and how their family and coworkers are doing. How much they know follows the `perception` level. `/lcdebug colonist` shows it.
- Prompt files the player never edited follow the mod's newer defaults after an update; edited ones are kept.
- Personalities: every colonist gets an archetype, picked mostly from their MineColonies skills with a share of chance, and a short background written once by the character model. Children get a description of their temperament instead of a past, sometimes take after a parent, and get an adult background when they grow up.
- Archetypes (`config/livingcolonists/archetypes/*.json`) and prompts (`config/livingcolonists/prompts/*.txt`) are plain files: edit, add or delete them. Eleven archetypes come with the mod.
- `/lcdebug colonist`, `archetype <id|reroll>`, `bio regenerate`, `prompt` and `reload` for operators (and in single-player worlds with cheats on). Without a name they act on your conversation partner, or else on the colonist you look at.
- A "Focus on colonist" key (unbound by default) picks the colonist you look at as your conversation partner without opening the chat, handy for `/lcdebug`.
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
