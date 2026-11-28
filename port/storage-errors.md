# Error map: Storage on 1.21.1 (measurement)

State of 2026-11-28, branch `port/1.21.1` with Lib compiling (#93) and main merged in (#94). One converter run over Storage
(`port/tools/errormap.sh --mod storage --symbols`) to size the Storage port before any Storage work is planned
(owner decision 2026-11-28). Nothing in Storage is fixed yet; Lib's rules, shims and overrides are untouched.

## How it was measured

- `convert.py --mod storage` copies `storage/{common,neoforge}/src/**` to `port/out/storage/**` and runs the same
  rules: `core.py`, plus `rules/storage_shared.py`, which applies copies of Lib's **package-wide** rules (gui's widget
  supertypes, saving's block entity IO, NBT getters, ProblemReporter and GameTest api; any Lib rule scoped to 8+ files)
  and four of Lib's file-scoped shim moves (`ItemStackTemplate`, `Quadrant`, `CuboidFace`, `Transparency`) to Storage.
  No Storage-specific rewrite was written.
- `projects/storage-common`, `projects/storage-neoforge` (only with `-PwithStorage=true`) compile against the
  generated Lib's classes. Optional mods stay off as in main's default build: only KubeJS, Rhino and FTB Teams have a
  1.21.1 line in the port's catalog, so the JEI, REI, AE2, RS, Curios and FTB Library compat shows as missing packages.
- Storage's GameTests see Lib's gametest classes (`shim.gametest`). On main they can't; without that, 1,640 more
  errors (the 26.1 test registries, `TestData`, `RegisterGameTestsEvent`) show in Storage's GameTests.
- Areas come from the path (`STORAGE_AREAS` in `tools/errormap.py`), and javac runs once per area over that area's
  files. **Masking caveat:** an area counts only its own files, and javac reports a missing type only where it is used.
  Fixing a shim or a signature can uncover new errors in method bodies, as it did in Lib (saving: 75 errors in one
  run, about 480 compiled alone). Here the per-area and single-run totals match (2,892 and 2,891 before main's
  last merge), because Lib arrives as compiled classes, so no area hides another yet.

## Totals

| run | errors | files with errors |
|---|---:|---:|
| core rules only | 5,057 | 663 |
| + Lib's package-wide rules and shim moves (this map) | **2,900** | **530** of 1,614 |
| for comparison: Lib at phase 1 | 1,060 | 185 of about 700 |

**1,084 of 1,614 Storage files compile** (67 %): 779 unchanged from main and 305 changed only by Lib's existing rules.
Core's contract moves already point Storage at Lib's shims (`ValueInput`/`ValueOutput`, `TagValue*`, `Capabilities`,
the GameTest types, `GuiGraphicsExtractor` and the input events); Lib's package-wide rules (the `Extract*` widget
bases, block entity IO, `Nbt`, `ProblemReporter`, the GameTest api) then remove another 43 % of what is left.

## Per area

| area (path) | errors | files with errors / files | clean, unchanged | clean, changed by rules |
|---|---:|---:|---:|---:|
| gametest | 540 | 113 / 211 | 2 | 96 |
| compat | 683 | 26 / 43 | 12 | 5 |
| datagen | 294 | 22 / 30 | 6 | 2 |
| unit tests | 7 | 3 / 144 | 130 | 11 |
| rendering | 351 | 12 / 12 | 0 | 0 |
| gui | 130 | 50 / 188 | 86 | 52 |
| saving | 9 | 1 / 5 | 3 | 1 |
| network (network, cable, subnet, p2p, cluster, hub, controller, energy, signal, redstone, togglebus, priority) | 168 | 53 / 213 | 132 | 28 |
| storage (cell, drive, storageblock, storagechest, bulk, creative) | 67 | 26 / 58 | 23 | 9 |
| transfer (port, ioport, iface, bridge, part) | 83 | 25 / 69 | 32 | 12 |
| crafting | 131 | 48 / 122 | 60 | 14 |
| logic (logic, sensor, condition, display) | 118 | 52 / 205 | 139 | 14 |
| tools | 124 | 24 / 77 | 45 | 8 |
| other (machine, research, world, smartlabel, neoforge glue, ...) | 195 | 75 / 237 | 109 | 53 |

Notes: compat is 677 errors in JEI (233), REI (200), RS (124), AE2 (104) and Curios (16), all for want of their
1.21.1 jars; KubeJS, FTB Teams and the viewer glue have 6. In gametest, `Ae2BridgeGameTests` and `RsGameTests` hold
135 of those AE2 and RS errors. Saving-type errors also occur outside `save/`: 75 by message, mostly
`ValueInput cannot be converted to CompoundTag` and `loadCustomOnly(ValueInput)` in GameTests.

## Top missing symbols

All areas (count: symbol or message):

```
257 method does not override or implement a method from a supertype   (1.21.1 signatures: useItemOn, appendHoverText, ...)
110 class TooltipDisplay              74 class DataComponentGetter        67 GameProfile.name()
 77 package Material (Material.Baked) 49 ValueInput -> CompoundTag         45 sendOverlayMessage(Component)
 44 class TypedEntityData             44 class TextureSlot                40 package client.data.models.model
 38 BlockEntity.loadCustomOnly(ValueInput)                                38 new BlockEntityType<>(factory, blocks)
 34 class BlockStateModelPart         34 class SavedDataType               33 isWithinBlockInteractionRange
 32 class Orientation                 25 RecipeHolder.id().location()      20 Registry.getValue(ResourceLocation)
 20 class SubmitNodeCollector         19 addStandardInventorySlots         17 ChunkPos.pack(BlockPos)
```

By area, the most common:

- **gametest:** `GameProfile.name()` 46, `ValueInput` → `CompoundTag` 40, `loadCustomOnly` 38, `TypedEntityData` 23,
  RS's `StorageNetworkComponent` 15, `pack(BlockPos)` 13, `TooltipDisplay` 24.
- **rendering:** `Material.Baked` 66, does-not-override 36, `BlockStateModelPart` 33, `BakedQuad` 12,
  `DynamicBlockStateModel` 10, `SubmitNodeCollector` 10, `client.renderer.block.dispatch` 9.
- **datagen:** `TextureSlot` 73, `client.data.models.*` 77, `BlockModelGenerators` 28, NeoForge's
  `model.generators.template` 14, `Material` 14.
- **gui:** does-not-override 20, `extractTooltip` 12, `EditBox.keyPressed(KeyEvent)` 9, `SubmitNodeCollector` 10.
- **network, storage, transfer, crafting, logic, tools, other:** the same cross-cutting set: `TooltipDisplay`,
  `DataComponentGetter`, `ItemStackTemplate` (in 26.1 signatures Lib's shim does not cover), `Orientation`,
  `SavedDataType`, `BlockEntityType<>`, `TRY_WITH_EMPTY_HAND`, `sendOverlayMessage`,
  `isWithinBlockInteractionRange`, `addStandardInventorySlots`, `Recipe.assemble(input)`, `Level` → `ServerLevel`.
- **saving:** `orElse(int[])`/`orElse(long[])` on the typed NBT getters (6), `SavedDataType` (3).
- **unit tests:** 7 (`getUnitVec3i`, `orElseThrow`, a `Codec<UUID>` store).

## Candidates for core.py (not added; a parallel PR owns core.py)

Each was checked against NeoForge 21.1.268's sources or authlib 6.0.54:

| rule | errors | 1.21.1 fact |
|---|---:|---|
| `ImportMove` of `ItemStackTemplate`, `Quadrant`, `CuboidFace`, `Transparency` to Lib's shims (now in `storage_shared.py`) | about 100 | Lib's shims; Lib's packages scope them per file |
| `getGameProfile().name()` → `getName()`, `.id()` → `getId()` | 67 | `GameProfile.getName()`, `getId()` |
| `sendOverlayMessage(c)` → `displayClientMessage(c, true)` | 45 | `Player.displayClientMessage(Component, boolean)` |
| `isWithinBlockInteractionRange(p, d)` → `canInteractWithBlock(p, d)` | 33 | `Player.canInteractWithBlock(BlockPos, double)` |
| `RecipeHolder.id().location()` → `.id()` | 25 | `record RecipeHolder(ResourceLocation id, T value)` |
| `Registry.getValue(id)` → `get(id)` | 20 | `Registry.get(@Nullable ResourceLocation)` |
| `ChunkPos.pack(pos)` → `ChunkPos.asLong(pos)` | 17 | `ChunkPos.asLong(BlockPos)` |
| saving's `BlockEntityType` data-fixer argument and `Orientation` → `BlockPos` rules, outside GameTests too | 38 + 32 | Lib applies them to its GameTests only |

New shims of the same kind (26.1 types 1.21.1 lacks, used in every area): `TooltipDisplay` (110),
`DataComponentGetter` (74), `TypedEntityData` (44), `SavedDataType` (34). Lib's `shim.gametest` would have to move
out of Lib's gametest source set (to neoforge main or an api jar Storage's GameTests see), or Storage gets a copy.

## Proposed order (owner's order: saving + transfer, then the compile gate, then gui and rendering)

Sizes are errors and files as measured; effort is a guess against Lib (1,060 errors to green).

1. **Saving + transfer** (`save/`, cells and drives, ports, interfaces, bridges, parts; the saving-type errors in
   GameTests): about 160 path errors plus about 75 saving-type errors, in about 55 files. Small to medium. The risk
   is in behaviour, not in the count: cell contents, `KeptValue`/`KeptList`, `StorageSaveFormats` and the
   transfer-transaction paths must round-trip, so this package brings its GameTests along (dupes and losses).
2. **Compile gate** (network, crafting, logic, tools, other, unit tests, GameTests without AE2/RS): about 1,150 errors in
   about 365 files, mostly the cross-cutting set above. The core candidates and four new shims should take a large
   part of that. Large in files touched, mechanical in kind; it can be split by area once the core rules land.
3. **GUI and rendering** (gui, rendering, datagen models): about 775 errors in 84 files. Large: 26.1's block state
   model parts, `Material.Baked`, `SubmitNodeCollector` and the `client.data.models` datagen have no 1.21.1 shape, so
   most of it is whole-file overrides (below). The resources come with it: 26.1 item model definitions
   (`assets/*/items/*.json`) need datagen on 1.21.1, as noted for Lib in `errors.md`.
4. **Compat** (683, plus 135 in the AE2 and RS GameTests): needs a decision, not a package. Either add the 1.21.1 API
   jars and port each bridge (JEI 19, REI 16, AE2 19 and RS 2 differ from their 26.1 lines), or drop the 25 files from
   the 1.21.1 build with `.port-remove` at first.

Overall about 2.7 times Lib's starting count at a similar density (5.5 errors per broken file, Lib 5.7), but two
thirds of Storage's files already compile and almost half the errors (compat, rendering, datagen models) sit in 54
files.

## Whole-file override candidates (if no rule works)

28 files. Each would be a second hand-kept copy; most are candidates for version-neutral code in main instead
(moving the drawing or model building behind Lib's rendering api, as Lib did with its own models):

- rendering (12): `neoforge/client/` `LinkLineModel`, `StorageDriveModel`, `StorageCableModel`, `StorageModels`,
  `SmartLabelRenderer`, `QuadBaking`, `ControllerFaceModel`, `CraftingDisplayRenderer`, `DisplayPanelRenderer`,
  `CpuClusterModel`, `PartBaker`; `common/display/client/ScreenPainter` (common code that draws, so it can't hold an
  override neatly; the first to make version-neutral).
- datagen models (16): `neoforge/datagen/` `PortModels`, `MachineModels`, `CableItemModels`, `DisplayModels`,
  `ModelBoxes`, `SmartLabelModels`, `CrafterModels`, `FacingModels`, `ExpansionModels`, `PartModels`,
  `TerminalModels`, `EnergyCellModels`, `ControllerModels`, `ConditionModels`, `GeodeModels`, `StorageModelProvider`.
  Lib overrode its own two model providers; Storage's are spread over 16 files, so a shared Lib datagen api for
  both versions may cost less than 16 overrides.

Not counted: the 23 compat files and 2 bridge GameTests (step 4), which are per-mod-version code rather than overrides.

## Error list

`port/tools/errormap.sh --mod storage --symbols` regenerates the full list (by area and file, with the symbols);
`port/build/errormap-storage.log` holds every message.
