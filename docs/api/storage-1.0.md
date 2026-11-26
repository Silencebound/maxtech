# MaxTech Storage api for 1.0

Inventory of Storage's public api before 1.0, as of Storage 0.5.0. The api is `dev.silencebound.maxtech.storage.api`,
built from `storage/common/src/api` and published as `maxtech_storage-api`; it names Lib api types (`StackKey`,
`OwnerRecord`) and nothing else from Lib. Labels and the Javadoc checks are the same as for Lib, see
[lib-1.0.md](lib-1.0.md).

## Packages and types

| Package / type | Purpose | Label |
|---|---|---|
| **`storage.api`** | A storage network as other mods see it, read-only. | stable |
| `StorageNetwork` | Status, power, owner, access check, contents, energy. Server thread. | stable |
| `StorageNetwork.Status` | Online or paused. | stable |
| `StorageContents` | Totals by key with interned int ids, generations and immutable snapshots for off-thread readers. | stable |
| `StorageDevice` | A block entity on the network: role, operations per second, network change callback. | stable |
| **`storage.api.crafting`** | The pattern format and the provider interface the network sends crafting steps to. | experimental |
| `CraftingPattern` | Kind, inputs, outputs, source recipe; immutable. | experimental |
| `PatternIngredient` | Exact key, item tag or fluid tag. | experimental |
| `PatternInput` | Ingredient, amount, target face, returned. | experimental |
| `PatternOutput` | Key, amount, chance in (0, 1]. | experimental |
| `PatternProvider` | Runs patterns: lists them, says whether it can take a batch, takes inputs all or nothing. | experimental |

## Findings and decisions

The owner decided the findings of the 1.0 inventory on 2026-10-15; Storage 0.5.0 carries the breaking ones.

1. **Nullable returns in `StorageContents`.** Done: `latestSnapshot()` returns `Optional<Snapshot>`. `key(int)` keeps
   returning `@Nullable StackKey`: terminals resolve ids per entry, a hot path where `Optional` would allocate (PRD
   8.1), so it is the one documented exception.
2. **Dead enum values.** Done: `StorageNetwork.Status.CONTROLLER_CONFLICT` and `TOO_MANY_DEVICES`, not reported since
   Storage 0.3.0, are removed, with the analyzer's `TOO_MANY_DEVICES` warning and their lang lines. Nothing saves a
   `Status`; the network analyzer packet sends it by ordinal, and payloads are registered with the mod version, so a
   client and server of different Storage versions never exchange it.
3. **Crafting api maturity.** Done: `storage.api.crafting` is `@ApiStatus.Experimental` (package and every type) and
   may change in any Storage minor version until a second implementation (a bridge or a pipe) has used it.
4. **Foreign types.** Open: `StorageContents.Snapshot` exposes fastutil's `Int2LongMap` and `PatternProvider.push`
   takes an `Object2LongMap<StackKey>`; recommend keeping them (they avoid boxing on hot paths) and naming the choice
   in the package Javadoc.
5. **Chance representations differ.** Open: `PatternOutput.chance` is a float in (0, 1]; Lib's `ItemOutput.chance` is
   an int in hundredths of a percent.

## Breaking changes in Storage 0.5.0

- `StorageContents.latestSnapshot()` returns `Optional<Snapshot>` instead of a nullable `Snapshot`.
- `StorageNetwork.Status` lost `CONTROLLER_CONFLICT` and `TOO_MANY_DEVICES`; it is `ONLINE` or `PAUSED`.
- `storage.api.crafting` is marked experimental (no signature change).
- Storage requires Lib `[0.12,0.13)`.

## Javadoc pass (#202, non-breaking)

Javadoc for the compact constructors of `CraftingPattern`, `PatternInput` and `PatternOutput` and for the components of
the `PatternIngredient` records. No signature changed, so no version bump.
