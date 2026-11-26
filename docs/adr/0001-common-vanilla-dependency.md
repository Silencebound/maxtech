# ADR-0001: How `common` compiles against vanilla Minecraft

Date: 2026-09-29
Status: Superseded by ADR-0002 for how `common` compiles; the capability decision under Consequences still holds

## Context

Each mod is split into `common` (game logic against vanilla plus our `Platform` interface) and `neoforge` (the single `Platform` implementation and loader glue), see PRD §4.1. `common` needs Minecraft classes on its compile classpath but must not see NeoForge classes, otherwise the "no `net.neoforged.*` in common" rule is only a convention and a later Fabric port turns into an audit.

The build tool is ModDevGradle (MDG). Verified against **MDG 2.0.147** (latest release on maven.neoforged.net on 2026-09-29; plugin sources jar and the README at ModDevGradle commit `ec9eb84`), NeoForge `26.1.2.109`, NeoForm `26.1.2-1` (the NeoForm version NeoForge 26.1.2.109 declares in its userdev `config.json`).

## Options considered

1. **MDG vanilla mode in `common`.** Apply `net.neoforged.moddev` and set `neoFormVersion` instead of `version`. MDG then produces an unmodified Minecraft jar (plus sources) through NeoForm and adds it to the source set's compile classpath. This is the documented MDG setup for cross-loader subprojects ("Vanilla-Mode" in the README).
   - Pros: one plugin and one version source for both halves; IDE gets decompiled sources; NeoForge classes are absent, so a leak fails compilation instead of relying on review (the one exception is below); `addModdingDependenciesTo` covers the extra `api` source set.
   - Cons: NeoForm setup runs twice per mod (vanilla and NeoForge), costing a little CI time; `common` cannot use NeoForge types even where the PRD accepts a pragmatic leak (see Consequences).
2. **Compile `common` against the NeoForge artifact and rely on the import check.**
   - Pros: pragmatic leaks (`BlockCapability`, transfer API types) just work.
   - Cons: the seam is a lint warning only; NeoForge's patched methods on vanilla classes (`IBlockExtension` and similar) are invisible leaks that no import check catches.
3. **Hand-wire Mojang's client jar into a custom configuration without MDG.** Minecraft 26.1 is unobfuscated, so this is technically possible.
   - Pros: no NeoForm run for `common`.
   - Cons: we would maintain our own download, library resolution and sources wiring; loses the MDG-managed IDE integration and access-transformer support.

## Decision

Option 1. `maxtech.common` (the convention plugin for every `common` project) applies MDG in vanilla mode with `neoFormVersion` from the version catalog and calls `addModdingDependenciesTo` for the `api` source set. The NeoForm version is kept in lock-step with the one the pinned NeoForge build declares.

MDG attaches the Minecraft artifacts by making the source set's `compileClasspath`/`runtimeClasspath` extend its own configurations (`ModDevArtifactsWorkflow.addToSourceSet`), not through `api`/`implementation`. A project dependency on `common` therefore does not carry vanilla Minecraft into the `neoforge` project, where it would clash with NeoForge's patched Minecraft.

The `neoforge` project depends on `common` with `compileOnly` and registers `common`'s `main` and `api` source sets in its `mods {}` block. FML's `InDevFolderLocator` (loader 11.0.15) builds each mod from the `fml.modFolders`/`MOD_CLASSES` list independently of the JVM classpath, so `common` is loaded at dev runtime without also putting a `common` jar on the classpath. The release jar packs `common`'s classes and resources into the `neoforge` jar.

## Consequences

- A NeoForge type in `common` is a compile error, not a warning, with one exception: NeoForm pulls `net.neoforged:mergetool` onto the vanilla compile classpath, so `net.neoforged.api.distmarker.{Dist,OnlyIn}` still compile in `common`. The Gradle import check catches those, and anything a later NeoForge `compileOnly` would let through.
- **Capabilities** (decided 2026-09-29): an early PRD draft put `BlockCapability<EnergyContainer, Direction>` in the api package, which does not compile here. Our own capability types (energy, network nodes, block info) instead go through `Platform.capabilities()`: the api holds the interface (`EnergyContainer`) and `common` holds a `BlockLookup<EnergyContainer>` handle. NeoForge's `BlockCapability` still does the work underneath; the wrapper is a pass-through, and the api jar stays loader-neutral for third-party addons. M1 adds a cached variant over `BlockCapabilityCache` for per-tick callers.
- **Item/fluid transfer** (expected change in M1): NeoForge's transfer API is entangled with NeoForge fluids (`FluidStack`, `FluidType`) and capabilities, and wrapping it would duplicate resources and transactions and add conversions on pipe hot paths (PRD §8.1). When M1's basic pipes need it, `common` projects switch from vanilla mode to compiling against NeoForge, as PRD §4.1 describes. ADR-0002 then supersedes this one, the import check becomes an allowlist (transfer, fluids and capability types accepted with a `// PLATFORM-LEAK:` marker; anything else warns), and the PRD §4.1/§5.1 wording is updated. The cost is that NeoForge's patched methods on vanilla classes become callable from `common` without any check seeing them; acceptable while a Fabric port is unscheduled.
- Two NeoForm setups per mod. MDG skips decompilation when `CI=true` (default since MDG 2.0.136), so CI cost is small; local setup is cached in the Gradle user home.
- Bumping NeoForge means checking its userdev `config.json` for the matching NeoForm version and bumping both catalog entries together.
