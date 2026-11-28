# PRD: MaxTech Lib and Storage — Minecraft NeoForge mods

**Target:** Minecraft 26.1.2, Java 25 — **NeoForge 26.1.2.x** (latest 26.1.2 build; 26.1.2.95+ known good), Gradle 9.4+. NeoForge-only, structured so a Fabric port is a bounded task later.
**Version note:** staying on 26.1.2 for v1 (decided). 26.2/26.3 exist and 26.2+ adds a Vulkan backend; a port is a post-v1 task. Keep rendering code behind Blaze3D APIs (no raw GL) so the port stays cheap.
**Status:** Draft v1
**Owner:** Silencebound
**License:** LGPL-3.0-only for both mods, code and assets (decided 2026-10-15). Addon authors may depend on the api jar freely; free to use in any modpack.

**Per mod:** this file holds the shared vision, the mod and loader layout, Lib's shared systems, config, process, performance and cross-cutting requirements. Storage's requirements are in `storage/PRD.md`. Section numbers are kept across the two files, so a reference to "PRD §N" means:

| Sections | File |
|----------|------|
| §1–§4, §5 (5.2, 5.7–5.10), §8, §9 (M0, M7b, M8), §11, §12 | `PRD.md` |
| §7.2, §7.3, §9 (M6, M7) | `storage/PRD.md` |

---

## 1. Vision

A digital storage network for NeoForge in the spirit of AE2 and Refined Storage, built on a small shared code library: channel-limited storage networks, terminals, autocrafting and a research-driven electronics progression, with sane defaults and a small number of high-impact config switches.

## 2. Goals

1. A stable Lib API that addons (ours and third-party) compile against.
2. A shared network *topology* layer (discovery, invalidation, split/merge, chunk handling) used by storage cables, with transport logic owned per network type.
3. A standalone storage addon with an AE2/Refined-Storage-inspired digital storage network and autocrafting (ADR-0010).
4. Cross-mod features are optional and guarded.

## 3. Non-goals

- Client-side-only features (shaders, cosmetic-only blocks) beyond what is needed for the above.
- Fabric support in v1 (structure kept port-ready; see §4.1).

## 4. Mods

| Mod | Mod ID | Purpose | Depends on |
|-----|--------|---------|------------|
| Lib | `maxtech_lib` | API, conduit and network frameworks, FE helpers, GUI, config, datagen helpers; no player-facing content apart from the facade item (ADR-0014) | none |
| Storage | `maxtech_storage` | Digital storage network, autocrafting, AE2/RS bridges (ADR-0010); standalone, runs on FE until it switches to Lib's energy system (§5.11) | Lib |

Each mod ships as one NeoForge jar. Lib additionally publishes an `-api` jar.

### 4.1 Loader strategy: NeoForge-only, port-ready

- **NeoForge is the only supported loader in v1.** No Fabric build, no dual testing, no Architectury.
- To keep a future Fabric port bounded rather than a rewrite, each mod is split into `common` (game logic, compiled against Minecraft and our own `Platform` interface; of NeoForge only its transfer, fluid and capability types, plus model-data types in `getModelData()` overrides, are used, see below) and `neoforge` (the single `Platform` implementation, entrypoint, datagen, JEI/Jade/KubeJS glue).
- `Platform` covers only what a second loader would need to replace: registry helpers, capability/API lookup, item/fluid transfer views, networking, config file handling, lifecycle events. It has one implementation; don't over-abstract it.
- `common` compiles against NeoForge but may only use its transfer, fluid and capability types, plus `ModelData`/`ModelProperty` in block entities' `getModelData()` overrides, each marked with a `// PLATFORM-LEAK:` comment explaining why; everything else goes through `Platform`. A Gradle check reports violations as a **warning** (not a build failure) so pragmatic exceptions are visible but not blocking (ADR-0002).
- Transfer: `common` uses NeoForge's transfer API types directly where an abstraction would be pure ceremony, but only through the `Platform.transfer()` accessor so those call sites are easy to find.
- Fabric support is an explicit non-goal for v1 and reconsidered only after v1 ships and there is demand.

## 5. Lib requirements

### 5.2 Networks (shared topology, per-type logic)

- **What a conduit is:** Lib's generic framework for blocks that connect to their neighbours: cables now, later pipes and wires. One `ConduitType` per block, face modes, shapes and network membership. It is unrelated to vanilla's Conduit block, and players see "cable" in every text, never "conduit".

- Lib provides **topology only**: the network graph handles node discovery via a connection predicate, invalidation on block change, split/merge, unloaded-chunk modes, anchors, tick scheduling and debug/inspection. It knows nothing about what flows through the graph.
- Each network type supplies a `NetworkLogic` that receives graph events (rebuilt, split, merged, node added/removed) and owns its own transport model; storage counts channels along derived routes and registers devices. No shared "payload" abstraction across types.
- One per-level `NetworkManager` owning graphs keyed by network type. Graph nodes are keyed by `(pos, conduitType)`.
- **Conduit blocks (ADR-0012):** every cable kind is a `ConduitType` registered by its mod, and each type is its own standalone block; one conduit per block. Lib provides the conduit block framework and one shared block entity type. Rendering is baked-model composition with part caching keyed on `(type, connectionMask, color)` plus per-face ring parts (ADR-0005) via model data, no BER. Facades apply to a conduit block.
- **Per-face configuration (ADR-0005):** each face where a conduit meets a block has a mode (insert, extract, both or disabled), cycled with the wrench and shown as a coloured connector ring on the arm; between two conduits a face is connected or disabled. Each conduit type declares its supported modes and default.
- **Colors:** 16 dye colors + uncolored, per conduit, set by dye or dyed item. Colored conduits connect only to same-color or uncolored conduits of the same type; the color tints the model.
- Invalidation on place/break/neighbour change; rebuild or split/merge on next level tick. Graphs are not persisted; they are rebuilt on chunk load.
- All network logic runs from level tick events, never from per-block-entity ticking.
- **Unloaded chunks (configurable):** server config `networks.unloadedChunkMode`:
  - `truncate` (default): the graph is built only from loaded chunks; edges into unloaded chunks are cut and re-attached when the chunk loads. Networks may split into temporarily separate graphs.
  - `pause`: a network that touches an unloaded chunk idles entirely until all its chunks are loaded (uses a per-network chunk set tracked at rebuild time).
  - Optional **network anchor** block (config `networks.allowAnchors`, default on) that chunk-loads its own chunk plus the chunks of the network it sits on, up to a configurable radius/limit, with an energy upkeep cost. Anchors are the supported way to keep long-distance networks alive regardless of mode.
  - Digital storage inherits this behaviour.

### 5.7 Config

- Common config schema, loader-agnostic files — server config for balance/behaviour, client config for visuals.
- **Audience presets:** a top-level `preset = standalone | pack | custom` server option. `standalone` (default) tunes defaults for playing alongside vanilla: gentler recipe costs and vanilla-ore reuse. `pack` tunes for modpack makers: stricter costs, hooks for KubeJS-driven recipe replacement, and raw values exposed. Presets are just default bundles; every option remains individually overridable, and `custom` means "don't touch my values on upgrade". Preset defaults are data (JSON) so packs can ship their own.
- Keep switches few and impactful: preset, unloaded-chunk mode, network anchors, recipe cost multiplier, and Storage's balance numbers (channels, power, cell limits).

### 5.8 Versioning & releases

- **Semantic versioning per mod, independent releases.** Lib: `MAJOR.MINOR.PATCH`; api jar version tracks Lib. Storage uses internal Lib packages, so each Storage release pins exactly one Lib minor in its `neoforge.mods.toml` (e.g. `[0.9,0.10)`); third-party addons on the api jar may declare a wider range.
- Breaking `api` change → Lib MAJOR bump; addons re-release only if they need the new version.
- Each mod has its own changelog and tag (`lib-v0.2.0`, `storage-v0.9.1`). CI publishes per-mod jars to CurseForge/Modrinth.
- Minecraft version support: one MC version line at a time (26.1.2 for v1); port when a new line stabilises.
- **Repository:** public GitHub repo `Silencebound/maxtech` (all MaxTech mods as folders). CI via GitHub Actions on GitHub-hosted runners.

### 5.9 Code quality

- Mixins only in Lib, one ADR each; Storage is mixin-free.
- Error Prone + NullAway (JSpecify `@NullMarked`) in the convention plugin, failing the build.
- Javadoc on all `api` public surface; internals only where non-obvious.
- JUnit for pure logic in `common`, GameTests for level behaviour, `perf/` GameTests for budgets. Bug fixes ship with a regression test.

### 5.10 API stability

- Everything under `dev.silencebound.maxtech.lib.api` is the public surface. Breaking changes require a major version bump.
- Internal packages are not for addon use; document this in the api package-info.

### 5.11 Energy (planned, MLE)

- Lib gets an own MaxTech energy system, used by every MaxTech mod; plan, decisions and open questions in `milestones/MLE.md` (milestone notes) (owner decisions 2026-10-15).
- An own unit, the MaxTech Unit (MU), with a config-set conversion rate to and from FE, default 1:1. FE from other mods comes in at the edges and MaxTech networks may send energy out, which packs can limit or switch off; no converter blocks.
- Energy stays in blocks and moves block to block. One batched pass per network per tick that sleeps while supply and demand are unchanged, with linear brownout, priorities and diagnostics. Lib keeps the cable framework; energy cables, wireless links and chargers come with the future machines mod, not with v1.
- Built before Storage 1.0; Storage switches from FE to it before the release.

## 8. Cross-cutting requirements

### 8.1 Performance (first-class requirement)

Large multiplayer bases with thousands of cables, pipes and machines must not degrade tick time. Concrete rules:

**Ticking**
- Storage cables have **no ticker**. Their logic runs from the per-level `NetworkManager` in one level-tick pass per network type.
- Machines **sleep** when idle: no recipe possible, output full, or no energy: the ticker returns immediately; woken by inventory/energy change events, neighbour updates or redstone, not by polling. Target: an idle machine costs ~0 per tick.
- Any recurring work uses a shared scheduler with tick-spreading (work distributed across ticks by hash of position) so hundreds of devices don't all fire on the same tick.

**Networks**
- Graph updates are **incremental**: adding or removing one node touches only its neighbours; full rebuilds are reserved for splits and are bounded by a per-tick node budget.
- Digital storage indexes contents by item/fluid key (hash map, not linear scan); terminal queries are O(1) per key and network content changes are delta-synced to open terminals only.

**Capabilities and lookups**
- Never call `level.getCapability` / `getBlockEntity` in a hot loop. Cache with NeoForge's `BlockCapabilityCache` and invalidate on neighbour change.
- Recipe matching uses an input-signature cache per machine; a machine re-scans recipes only when inputs change.

**Memory and allocation**
- Hot paths avoid allocation: primitive collections (fastutil, shipped with Minecraft), reused mutable `BlockPos`, no streams or lambdas in per-tick code.
- Energy values are `long`; no boxing.

**Networking / sync**
- Only dirty fields sync, coalesced to at most one packet per block entity per tick; GUI state syncs only while a container is open.
- No block entity sends data to clients who don't have the chunk loaded or the GUI open.

**Client**
- Cable rendering is batched per chunk section (baked models); no per-block BERs.
- Machine models are static baked models with animated parts driven by block state or a small shared renderer, never per-BE geometry rebuilds.

**Budgets and verification**
- Targets on a mid-range server (measured with Spark): storage network with 100k stored items and 10 open terminals < 0.3 ms/tick; storage budgets for zone rebuilds, idle ports, burst inserts and saves are set with their perf GameTests (§7.2).
- A `perf/` GameTest suite builds these scenarios and asserts the budgets; they fail locally and only warn in CI, whose hosted runners vary too much to block on (owner 2026-11-25). Hot-path changes get their perf GameTests run on a quiet machine before merging.
- `/maxtech profile` command dumps per-network tick cost for server admins.

### 8.2 Other cross-cutting requirements
- **Multiplayer:** all logic server-authoritative; sync only what the client renders.
- **Datapack-friendly:** recipes and materials via JSON.
- **Localization:** all strings through lang files; datagen for `en_us`.
- **Accessibility:** client option `conduitColorLabels` to render a small letter/pattern overlay on colored conduits and cables; tier badges use shape + color, never color alone.
- **Save compatibility:** no world-breaking changes within a major version. Registry renames ship with a remapping entry; data-component format changes ship with a migration. Removing content requires a major bump and a deprecation release first.
- **Soft dependencies:** JEI, KubeJS, Jade, AE2, Refined Storage declared `type = "optional"` with a minimum version range; the mods must load and run with none of them present.
- **Creative tabs:** one tab per mod, ordered by tier then by function.
- **Harvesting:** Storage blocks and conduits drop with a pickaxe of any tier (wrench is faster and silent). No harvest-level gating.
- **Compat (v1):**
  - **JEI first** (categories, recipe transfer to the pattern terminal); EMI best-effort.
  - **KubeJS first-class.** Every Storage recipe type has a KubeJS recipe schema built on Lib's helpers. Builders in common `api` are designed so the KubeJS layer is a thin wrapper, not a second implementation. Recipe JSON format is documented and stable for CraftTweaker/datapack users, but no CraftTweaker plugin in v1.
  - **Jade primary** for block info. All overlay data goes through a common `BlockInfoProvider` interface in Lib's api; the Jade plugin consumes it. WTHIT/TOP adapters can be added later without touching Storage's blocks.
  - **Guide book:** an in-game guide drawn by GuideME (back since MGM; Lib's own guide from M7G is retired). GuideME is bundled inside Lib's jar (jar-in-jar), so players install nothing extra. Pages are short GuideME Markdown in the repo, written as each milestone lands; each mod declares its guide and its own book item. Progression is also taught through JEI, rich tooltips and advancements.
  - **Advancements grow with each milestone**, not in one pass at the end.
  - **AE2 / Refined Storage bridge: required before 1.0.** Baseline (capability-level) comes free: our interface/bus blocks expose standard item/fluid handlers so their storage buses attach to ours and vice versa. On top of that, Storage ships a **network bridge** block per external mod (`storage.compat.ae2`, `storage.compat.refinedstorage`, optional deps) that mounts the foreign network's contents into ours as a virtual storage cell (and ours into theirs where their API allows), so terminals show a combined inventory. Crafting-request forwarding across the bridge is a stretch goal, not a 1.0 blocker. Bridges are read-through, not copies; no duplication of stored data.
  - **Curios (optional):** carried gadgets (wireless terminal, crafting tablet, logic remote, pocket uplink, portable cell, restock belt) work from Curios slots, found through Lib's carried-item finder (`lib.api.carried`); `storage.compat.curios`.
  - Later: CC:Tweaked peripherals, Trinkets/Accessories.
- **Testing:** GameTest for network split/merge, storage operations and autocrafting; JUnit for pure logic.

## 9. Milestones

| # | Milestone | Deliverables |
|---|-----------|--------------|
| M0 | Skeleton | Monorepo, convention plugins, common/neoforge split per mod, `Platform` service (single impl), dev runs, CI build |
| M7b | Compat | KubeJS plugin (recipes), AE2 + RS network bridges |
| MLE | Lib energy | MaxTech energy unit in Lib, energy cables, FE edges, diagnostics, wireless links and chargers; Storage switches from FE (`milestones/MLE.md` (milestone notes)) |
| M8 | Polish / release | Config review, docs, balance pass, CurseForge/Modrinth release |

## 11. Audience

Primary: both standalone players and modpack makers, served by the `standalone` / `pack` config presets rather than by compromising one for the other. Standalone is the default because most first-time installs are single-mod; pack makers are expected to flip the preset and script via KubeJS.

## 12. Success criteria

- Storage works standalone with Lib alone; GuideME is bundled inside the Lib jar.
- With AE2 or Refined Storage installed, a bridge block shows the foreign network's inventory in our terminals.
- No cable block entity ticks; all §8.1 budgets pass on a quiet machine.
- A third party can build a working addon using only the published Lib api jar.
