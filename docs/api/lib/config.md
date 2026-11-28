# `lib.api.config`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.config`** | Config files with presets for every MaxTech mod. Moved from `lib.config` and `lib.platform` for Farms (FM1). | experimental |
| `Configs` | Entry point: `presetConfig(modId, anchor)`, and `builder`/`register` for plain files. | experimental |
| `PresetConfig` | A server config whose defaults come from `<modid>/presets/*.json`. | experimental |
| `ConfigBuilder`, `ConfigFile`, `ConfigType` | Declaring values, the registered file, which file. | experimental |
