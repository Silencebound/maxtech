# External reference

## Local reference clones (preferred)

`scripts/setup-reference.sh` clones the repositories below into `~/mc-reference/` (override with `MAXTECH_REF_DIR`). That directory is granted to Claude Code via `permissions.additionalDirectories` in `.claude/settings.json`, so `grep`/`rg` across it works without prompts. Treat it as read-only.

| Directory | What it is | Use it for |
|-----------|------------|------------|
| `NeoForge/` | NeoForge sources, `26.1.x` branch | The truth for any NeoForge API question |
| `Documentation/` | NeoForge docs (Markdown) | Concepts, examples, migration notes |
| `ModDevGradle/` | Build plugin | Run configs, vanilla mode, multi-project |
| `EnderIO/` | EnderIO | Cable (EnderIO "conduit") model, rendering, interaction; its bundle block is not ours (ADR-0012) |
| `AE2/` | Applied Energistics 2 | Grid/network, storage keys, autocrafting; bridge API |
| `JEI/`, `KubeJS/`, `Jade/` | Compat targets | Plugin APIs |

Reference mods are for understanding approaches. Do not copy code; licenses differ and the PRD's designs are our own.

## Minecraft sources

The patched Minecraft sources are in any checkout's `<mod>/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.109-sources.jar` (read with `unzip -p <jar> net/minecraft/...`). A fresh worktree has none until its first build, so read the main checkout's copy.

## Worktree agents

A worktree-isolated agent's Bash call is refused when it could run `git` in a form the harness cannot check: a script fed through a heredoc (`python3 - <<EOF`), a command built from a shell variable (`unzip -p "$J"`), or `git` chained after other commands that use variables. Edit files with the Edit and Write tools and keep `git` calls plain.

## Online sources (when the clones don't answer it)

Read the actual sources first (see CLAUDE.md, "Toolchain facts").

## Minecraft 26.1 / NeoForge
- NeoForge docs: https://docs.neoforged.net/ — select the 26.1 version in the version picker. Registries, capabilities, transfer API, networking, datagen, config.
- NeoForge news/blog: https://neoforged.net/ — release posts for each version line list breaking changes; read the 26.1 post and the 21.9 transfer-API migration post.
- NeoForge GitHub: https://github.com/neoforged/NeoForge — search `src/main/java/net/neoforged/neoforge` on the `26.1.x` branch for real usages.
- ModDevGradle: https://github.com/neoforged/ModDevGradle — README covers vanilla mode, run configs, multi-project setups, source-set registration.
- NeoForge Discord `#modder-support-26` is where undocumented 26.1 changes get answered; not fetchable, but worth knowing for the owner.

## Minecraft version notes
- Fabric 26.1 announcement (loader-agnostic change list for Mojang's 26.1, useful even on NeoForge): https://fabricmc.net/2026/03/14/261.html
- 26.1 is unobfuscated; Mojang's names are the real names. No mappings.

## Compat targets
- JEI: https://github.com/mezz/JustEnoughItems/wiki — plugin API, recipe categories, recipe transfer.
- KubeJS: https://kubejs.com/ — recipe schemas, events; check the 26.1 branch of the repo for API.
- Jade: https://github.com/Snownee/Jade/wiki — provider API.
- Applied Energistics 2: https://github.com/AppliedEnergistics/Applied-Energistics-2 — `api` package for the storage bridge (M7b).
- Refined Storage: https://github.com/refinedmods/refinedstorage2 — API module for the bridge (M7b).

## Reference implementations (read, don't copy)
- Applied Energistics 2 (above) — grid/network and storage key model.
- EnderIO: https://github.com/Team-EnderIO/EnderIO — cable rendering and interaction (EnderIO calls them conduits; we dropped its bundle block, ADR-0012).

## Future port
- Fabric docs: https://docs.fabricmc.net/ — only relevant if a Fabric port is scheduled.
