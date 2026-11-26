# ADR-0003: Config presets track what they wrote

Date: 2026-09-29
Status: Accepted

## Context

PRD §5.7 asks for a server option `preset = standalone | pack | custom`: presets are default bundles stored as JSON so packs can ship their own, every option stays individually overridable, and `custom` means "don't touch my values on upgrade". NeoForge's `ModConfigSpec` has fixed defaults and writes typed values to a TOML file; it cannot tell a value the owner edited from one that is still at its default.

## Options considered

1. **Track what the preset wrote.** Values stay typed. A state file records the value the preset last wrote per path; on each load a value that still equals that record follows the current preset, anything else counts as an edit and is kept.
   - Pros: normal config file and config GUI; switching preset and mod updates move untouched values; edits are never lost.
   - Cons: an extra state file; deleting it makes every value count as untouched once.
2. **A `preset` placeholder string per option**, replaced by a real value to override.
   - Pros: very explicit, no state.
   - Cons: every option becomes a string, losing range and enum validation in the file and the GUI.
3. **Apply the preset only when the file is created.**
   - Pros: trivial.
   - Cons: updates never deliver new defaults, so `standalone` and `pack` behave like `custom`.

## Decision

Option 1, chosen by the owner on 2026-09-29. Built-in presets live in `<mod>/common/src/main/resources/<modid>/presets/<name>.json` as flat objects of dotted config paths. A file of the same name in `config/maxtech/presets/` replaces a built-in one; new names add presets. Every preset is layered on `standalone`, which also supplies the spec defaults, so preset values never appear in Java. `custom` skips preset handling entirely. The state file sits next to the config (`maxtech-server-preset.json`). Only a config loaded from a file is processed; a client's synced copy of the server config is left alone.

## Consequences

- New balance options need an entry in the owning mod's `standalone.json` (and in `pack.json` where the pack value differs); `PresetConfig` fails at startup if `standalone.json` lacks one.
- Unknown keys or wrongly typed values in a preset are logged and skipped, not fatal.
- Deleting the state file makes every value count as untouched, so the next load resets all of them, edits included, to the preset.

## Multi-mod use (2026-09-29)

Every suite mod with balance values gets its own preset-backed server config through core's `PresetConfig`; core keeps the only implementation. A mod ships its built-in presets at `<modid>/presets/<name>.json` in its `common` resources, server owners override them in `config/<modid>/presets/`, and the state file is named after that mod's config file (`<modid>-server-preset.json`). Each mod has its own `preset` value, so mods can run different presets; the rules above apply to each mod on its own. Addons pass a class from their own jar so core can read their preset resources.
