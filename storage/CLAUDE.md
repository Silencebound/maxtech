# CLAUDE.md — MaxTech Storage

Rules for the Storage addon (`maxtech_storage`). The root `CLAUDE.md` holds the rules for every mod; this file adds Storage's. Storage is a standalone mod on Lib alone; GuideME is bundled inside its jar. Storage's requirements are in `storage/PRD.md` (§7.2, §7.3 and its milestone rows); API facts found while building Storage are in `.claude/skills/neoforge-26-1/storage-facts.md`.

## Architecture

- **Energy:** Storage runs on the MaxTech energy system in Lib (MU, `milestones/MLE.md` (milestone notes)); FE only at the edges
  through Lib's FE adapters and the configurable rate. `lib.api.energy` stays experimental until the machines mod uses it.
- **Cables:** Storage cables are conduits too but follow Storage's own rules: tiers by channel count, the M6 cable looks.
- **Cable parts:** devices such as terminals and ports that sit inside a storage cable's block, up to six per cable, are part of Storage's design.
- **Machines:** Storage's machines (fuel generator, lithograph, fabricator, assembler) are single-tier MU machines on Storage's own small machine classes.
- **Resources:** Storage is outside the material system: its resources (lumen quartz, tantalite, niobium and later ones) are its own items, ores and recipes.
- **Ores:** Storage is standalone and uses normal NeoForge ore features with config.

## Storage network

- Storage networks are limited by channels (1 device = 1 channel along its route; cable, hub and root-link capacities), counted on topology changes only, never per transfer or tick. Access is instant; only port and bus speed limit automation. One controller per network; a second one goes on standby.
- Storage network: intern keys to int IDs, primitive maps only, palette-based cell persistence, delta sync to terminals, snapshot reads. See `storage/PRD.md` §7.2. Never store `ItemStack` objects in the index or serialise full stacks per slot.
- Storage cell types go through the `CellType` registry; item/fluid/universal/void/creative in v1. Type limits are per-cell and config-gated (off by default); never bake a type cap into the data model.
- AE2/RS bridges are a 1.0 requirement (M7b). Bridge code lives in `storage.compat.<modid>` and follows the optional-dependency rules; bridges are read-through views, never copies of foreign storage.

## Autocrafting

- Storage has its own planner/executor in Storage. Within Storage there is one planner/executor; extended features (planner GUI, tiered CPUs, stock keeping, machine-aware dispatch, partial results) are config-gated, default on, with `classic` mode = AE2 behaviour. Never fork the planner per mode. Design the job tree so a v2 distributed-CPU split is possible.
- Autocrafting patterns come from the pattern terminal *and* machine export; both must write the identical pattern format.

## Look, guide and recipe viewers

- Storage's GUI style is the Accent Frame (cyan on graphite, pixel art that fits Minecraft); the look and its widgets
  live in Lib (`lib.client.gui.accent`) and Storage uses them.
- Storage has its own guide book (`maxtech_storage:guide`), drawn by the bundled GuideME; pages are short and concise like AE2's (rules and facts: the `guideme` skill).
- Every Storage recipe type needs a JEI category (`compat.jei`) and a KubeJS schema (`compat.kubejs`, built on Lib's helpers); GameTests fail when one is missing (#308, #309).
