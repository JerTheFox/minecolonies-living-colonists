# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `/lc talk <message>`: talk to the colonist you look at, or the nearest one within 8 blocks. The reply comes from the language model and appears in chat like a player message, for players within 32 blocks; hovering the name shows the profession and colony.
- Project skeleton from the NeoForge 1.21.1 ModDevGradle MDK: mod id `livingcolonists`, `runClient` and `runServer` with MineColonies 1.1.1405 and its dependencies.
- Exact version pins for Minecraft 1.21.1, NeoForge 21.1.264 and MineColonies 1.1.1405.
- Mod logo in the mod list.
- Common config `livingcolonists-common.toml` for the OpenAI-compatible API: base URL, model, API key environment variable, timeout, max tokens, temperature and extra request parameters. Defaults point to DeepSeek (`deepseek-flash`).
