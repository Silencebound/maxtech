# Error map: Lib on 1.21.1 after phase 1

State of 2026-11-28, branch `port/1.21.1`, generated from main `60ca3fb` with `port/tools/errormap.sh` (one javac over
all seven Lib source sets, so common's errors don't hide neoforge's). Regenerate the tables below with
`port/tools/errormap.sh > port/build/errormap.txt`; `port/build/errormap.log` holds every message.

**1060 errors in 185 files** (the spike, before the cheap rules and the vendored transfer api: 1490 in 227 files).
This is a lower bound: javac stops attributing an expression once a type in it is missing, so every package will
uncover more errors as its shims appear. Error counts are not effort either: the transfer bridge has few errors left
(the vendored api resolves) but the most design work (spike estimate 3-5 days), GameTests have many errors that one
registration shim clears at once.

| package | errors | files with errors | files owned (ownership.txt) |
|---|---:|---:|---:|
| transfer | 112 | 23 | 94 |
| rendering (+ model datagen) | 210 | 19 | 29 |
| gui | 240 | 31 | 53 |
| saving + GameTest | 384 | 74 | 85 |
| other (signatures, overrides, recipes, components) | 114 | 38 | 444 |

By area (an error's own topic; a file can mix areas): saving 224, gui 190, rendering 174, other 140, gametest 120,
overrides/signatures 82, transfer 64, recipes 29, components 16, naming 13, permissions 6, network 2.

What phase 1's rules already did (port/out/.port/report.txt): transfer api imports relocated (128 hits),
Identifier -> ResourceLocation and friends (~840), `ResourceKey.identifier()` -> `location()` (16), `Util` and
`advancements.criterion` package moves, the contract moves below, `_` -> `unused<N>` (6 sites in 2 files).

## Ownership: five disjoint work packages

Every generated `.java` file has exactly one owner, listed in `port/ownership.txt` (`<package> <path below port/out>`;
error-free files are assigned by path and content). Owning `lib/X.java` means owning everything that produces it:

- `port/override/lib/X.java` (whole-file replacement) and `port/override/lib/X.java.port-remove`,
- `port/shim/lib/X.java` when X is a shim file,
- and only that package's rules may change X.

Per package, its own files plus:

| package | rules | shim directories (below `port/shim/lib/`) | override files |
|---|---|---|---|
| transfer | `port/rules/transfer.py` | `common/src/api/java/dev/silencebound/maxtech/lib/shim/transfer/**` (vendored api + `bridge/`) | `port/override/<its files>` |
| rendering | `port/rules/rendering.py` | `neoforge/src/main/java/dev/silencebound/maxtech/lib/shim/client/render/**` | `port/override/<its files>` |
| gui | `port/rules/gui.py` | `neoforge/src/main/java/dev/silencebound/maxtech/lib/shim/client/gui/**` | `port/override/<its files>` |
| saving | `port/rules/saving.py` | `common/src/api/java/dev/silencebound/maxtech/lib/shim/saving/**`, `neoforge/src/gametest/java/dev/silencebound/maxtech/lib/shim/gametest/**` | `port/override/<its files>` |
| other | `port/rules/other.py` | `common/src/api/java/dev/silencebound/maxtech/lib/shim/misc/**` | `port/override/<its files>` |

Shared files, owned by the skeleton and changed only in small separate commits that every package rebases on:
`port/convert.py`, `port/rules/framework.py`, `port/rules/core.py`, `port/tools/**`, `port/projects/**`,
`port/settings.gradle.kts`, `port/gradle/**`, `port/ownership.txt`, `port/README.md`, this file.

Rules: scope every rule with `files=` to the package's own files; convert.py warns when a package rule changes a file
another package owns. A shim that lives in a package's directory may be used by everyone, so its public shape is a
contract (below). Moving a file between packages is a one-line `ownership.txt` change both owners agree on.

Note on the GUI/rendering split: `common/src/api/.../api/client/gui/**` (Accent widgets) is gui, `client/menu/
FacePreviewRenderer.java` and conduit/facade models are rendering; a gui file that draws a model calls a rendering
shim, it does not edit the renderer.

## Contracts between packages (what makes parallel work possible)

`core.py` already points every use of a 26.1-only type at a shim of **the same simple name and the same public
shape as 26.1's type** (CONTRACTS in core.py). So nobody waits for another package's design: the contract is
26.1's own API, which every owner can read in the 26.1.2 sources jar.

| shim type (package `dev.silencebound.maxtech.lib.shim.`) | 26.1 original | owner |
|---|---|---|
| `transfer.**` (vendored) | `net.neoforged.neoforge.transfer.**` | transfer |
| `transfer.bridge.Capabilities` (`Item/Fluid/Energy.BLOCK/ITEM`) | `net.neoforged.neoforge.capabilities.Capabilities` | transfer |
| `saving.ValueInput`, `ValueOutput`, `TagValueInput`, `TagValueOutput`, `ValueIOSerializable` | `net.minecraft.world.level.storage.*`, NeoForge `common.util.ValueIOSerializable` | saving |
| `gametest.TestData`, `FunctionGameTestInstance`, `TestEnvironmentDefinition`, `GameTestException` | `net.minecraft.gametest.framework.*` | saving |
| `client.gui.GuiGraphicsExtractor`, `client.gui.input.{MouseButtonEvent, MouseButtonInfo, InputWithModifiers, KeyEvent, CharacterEvent}` | `net.minecraft.client.gui/input.*` | gui |
| `misc.ARGB` | `net.minecraft.util.ARGB` | other |

A package that needs a further cross-cutting move (a 26.1 type used in other packages' files) asks for it in
`core.py`; it never puts such a move in its own rules file.

## What blocks parallel work

1. **Contract shims first, in hours, not days.** Transfer's vendored `StacksResourceHandler`,
   `SimpleEnergyHandler` and `ItemStackResourceHandler` need saving's `ValueInput`/`ValueOutput`; nine saving
   files (GameTest fixtures, `LibItemSlots`, `MachineItemStorage`) need transfer's `bridge.Capabilities`. Each
   package's first commit should land its contract types with 26.1's signatures (bodies may throw), so the other side
   compiles against them; behaviour follows.
2. **Vendored api vs. 1.21.1 types it uses** (`ItemStackTemplate`, `FluidStackTemplate`, `FluidInstance`,
   `StackCopySlot`, `TypedInstance`): transfer owns the fix inside the vendored files; Lib's own uses of
   `ItemStackTemplate`/`TooltipDisplay` stay with other. If both want a shared `misc.ItemStackTemplate`, that is a
   new contract move in core.py.
3. **Hidden errors.** Each package should run `port/tools/errormap.sh` after its shims land; newly surfaced errors in
   a file go to that file's owner, not to whoever uncovered them.
4. **Licence**: the vendored transfer api is LGPL-2.1-only inside the LGPL-3.0 Lib jar (port/NOTICE-transfer.md);
   needs the owner's OK before any public 1.21.1 build, not before the work.
5. **Resources** are copied unchanged (no rules on JSON yet): 26.1 item model definitions (`assets/*/items/*.json`),
   recipe and tag JSON formats may need rules or datagen on 1.21.1; that only shows at runtime, belongs to rendering
   (models) and other (recipes/tags), and is not in the counts above.

## Errors by package and file

Generated by `port/tools/errormap.py` (per file: errors, then the areas inside it).

```
total 1060 errors in 185 files

by area:
  saving         224 errors   43 files
  gui            190 errors   34 files
  rendering      174 errors   24 files
  other          140 errors   54 files
  gametest       120 errors   42 files
  override        82 errors   29 files
  transfer        64 errors   29 files
  recipes         29 errors    7 files
  components      16 errors    8 files
  naming          13 errors    8 files
  permissions      6 errors    2 files
  network          2 errors    2 files
by package (file owner):
  transfer       112 errors   23 files
  rendering      210 errors   19 files
  gui            240 errors   31 files
  saving         384 errors   74 files
  other          114 errors   38 files

[transfer]
    22  common/api: shim/transfer/item/ResourceHandlerSlot.java  (transfer 9, override 8, other 5)
    13  common/api: shim/transfer/fluid/FluidResource.java  (transfer 10, other 2, override 1)
    12  common/api: shim/transfer/item/ItemResource.java  (transfer 6, other 3, components 2, override 1)
    11  common/api: shim/transfer/item/ItemStackResourceHandler.java  (saving 4, transfer 3, other 2, override 2)
     8  common/api: shim/transfer/energy/SimpleEnergyHandler.java  (saving 4, transfer 2, override 2)
     8  common/api: shim/transfer/StacksResourceHandler.java  (saving 4, transfer 2, override 2)
     7  common/api: shim/transfer/item/PlayerInventoryWrapper.java  (other 6, override 1)
     6  common/api: shim/transfer/fluid/FluidUtil.java  (transfer 5, other 1)
     3  common/api: shim/transfer/resource/RegisteredResource.java  (other 3)
     3  common/api: shim/transfer/item/VanillaContainerWrapper.java  (transfer 2, override 1)
     2  common/main: menu/MenuTransfer.java  (transfer 2)
     2  common/api: shim/transfer/access/ItemAccess.java  (other 1, transfer 1)
     2  common/api: shim/transfer/fluid/FluidStacksResourceHandler.java  (transfer 1, other 1)
     2  common/api: shim/transfer/item/ItemUtil.java  (transfer 2)
     2  common/api: shim/transfer/item/ItemStacksResourceHandler.java  (transfer 1, other 1)
     2  common/main: inventory/MachineMoves.java  (transfer 2)
     1  common/api: shim/transfer/energy/EnergyHandler.java  (transfer 1)
     1  neoforge/main: neoforge/platform/NeoForgeTransferHelper.java  (transfer 1)
     1  neoforge/main: neoforge/transfer/ResourceHandlerBridge.java  (transfer 1)
     1  common/api: shim/transfer/access/StackItemAccess.java  (override 1)
     1  common/api: shim/transfer/fluid/BucketResourceHandler.java  (transfer 1)
     1  common/api: shim/transfer/fluid/ItemAccessFluidHandler.java  (transfer 1)
     1  common/api: shim/transfer/item/ItemAccessItemHandler.java  (transfer 1)
[rendering]
    49  neoforge/main: neoforge/client/ConduitModel.java  (rendering 31, other 14, naming 4)
    30  neoforge/main: neoforge/datagen/ContentDatagen.java  (rendering 26, other 4)
    26  neoforge/api: neoforge/api/client/conduit/ConduitItemModels.java  (rendering 18, other 8)
    22  common/main: client/menu/FacePreviewRenderer.java  (rendering 16, override 5, other 1)
    15  neoforge/main: neoforge/client/FacadeItemModel.java  (rendering 11, other 4)
    11  neoforge/main: neoforge/client/HighlightClient.java  (rendering 9, other 2)
     7  neoforge/main: neoforge/client/HighlightRenderTypes.java  (rendering 4, other 3)
     7  neoforge/main: neoforge/client/ConduitClient.java  (rendering 6, other 1)
     6  neoforge/main: neoforge/client/ConduitFaceUvs.java  (rendering 6)
     6  neoforge/main: neoforge/datagen/ContentModelProvider.java  (rendering 6)
     6  neoforge/main: neoforge/datagen/LibModelProvider.java  (rendering 6)
     5  neoforge/main: neoforge/client/GhostClient.java  (rendering 5)
     4  neoforge/main: neoforge/client/ConduitLayers.java  (rendering 4)
     4  neoforge/main: neoforge/client/FacadeClient.java  (rendering 3, other 1)
     4  neoforge/main: neoforge/client/NeoForgeFluidLook.java  (other 3, rendering 1)
     3  common/main: client/menu/FacePreviewState.java  (rendering 3)
     2  neoforge/test: neoforge/client/ConduitLayersTest.java  (rendering 2)
     2  neoforge/test: neoforge/client/ConduitFaceUvsTest.java  (rendering 2)
     1  neoforge/test: neoforge/client/ConduitModelHooksTest.java  (rendering 1)
[gui]
    25  common/api: api/client/gui/Accent.java  (gui 25)
    22  common/main: client/gui/ScreenComponents.java  (gui 13, rendering 5, override 4)
    17  common/main: client/menu/ToolkitScreen.java  (gui 13, override 4)
    16  common/main: client/menu/FacesWidget.java  (gui 11, override 4, other 1)
    15  common/main: client/gui/VirtualGrid.java  (gui 11, override 4)
    14  common/api: api/client/gui/AccentButton.java  (gui 10, override 4)
    13  common/api: api/client/gui/ScrollBar.java  (gui 9, override 4)
    13  common/main: client/gui/accent/SideTabs.java  (gui 10, override 3)
    12  common/api: api/client/gui/IconButton.java  (gui 8, override 4)
    12  common/main: client/gui/accent/AccentDemoScreen.java  (gui 8, override 4)
     9  common/api: api/client/gui/Icon.java  (gui 7, rendering 2)
     7  common/api: api/client/gui/AccentBar.java  (gui 5, override 2)
     6  common/api: api/client/gui/AccentTank.java  (gui 4, rendering 2)
     6  common/api: api/client/gui/AccentRow.java  (gui 6)
     5  common/api: api/client/gui/TextScale.java  (gui 5)
     5  common/main: info/ScannerItem.java  (gui 2, other 2, override 1)
     4  common/api: api/client/gui/AccentInset.java  (gui 4)
     4  neoforge/main: neoforge/compat/jade/InfoElements.java  (gui 3, other 1)
     3  common/api: api/client/gui/AccentProgress.java  (gui 3)
     3  common/api: api/client/gui/AccentToast.java  (gui 2, other 1)
     3  common/api: api/client/gui/AccentEnergyBar.java  (gui 3)
     3  common/api: api/client/gui/AccentReadout.java  (gui 3)
     3  common/api: api/client/gui/AccentTag.java  (gui 3)
     3  common/main: facade/FacadeItem.java  (gui 2, override 1)
     3  common/main: menu/MenuTestSession.java  (gui 3)
     3  common/main: client/gui/SidePanel.java  (gui 3)
     3  common/main: client/gui/PixelScale.java  (gui 3)
     2  common/api: api/client/gui/AccentStatus.java  (gui 2)
     2  common/api: api/client/gui/AccentPanel.java  (gui 2)
     2  common/api: api/client/gui/AccentTabRow.java  (gui 2)
     2  common/main: client/gui/RightAngleWire.java  (gui 2)
[saving]
    38  common/test: save/SaveFormatTest.java  (saving 33, other 5)
    24  common/main: conduit/ConduitBlockEntity.java  (saving 11, other 6, rendering 4, override 3)
    16  common/main: save/SaveFormat.java  (saving 13, other 3)
    14  common/main: inventory/LibItemSlots.java  (saving 12, transfer 2)
    11  neoforge/gametest: neoforge/gametest/RedstoneControlGameTests.java  (saving 6, gametest 3, other 2)
    10  common/main: conduit/ConduitContentsTag.java  (saving 10)
    10  common/test: conduit/ConduitContentsTagTest.java  (saving 10)
    10  neoforge/gametest: neoforge/gametest/InventoryGameTests.java  (saving 6, gametest 3, transfer 1)
     9  common/main: transfer/EnergyChannel.java  (saving 9)
     9  neoforge/gametest: neoforge/gametest/MachineAccessGameTests.java  (saving 6, gametest 3)
     8  common/test: conduit/ConduitRenderValueTest.java  (saving 8)
     7  common/main: save/VersionedBlockEntity.java  (saving 5, override 2)
     7  neoforge/gametest: neoforge/gametest/TestEnergy.java  (saving 4, other 2, transfer 1)
     7  neoforge/gametest: neoforge/gametest/ToolkitDemo.java  (saving 4, other 2, transfer 1)
     7  neoforge/gametest: neoforge/gametest/MenuSlotsGameTests.java  (gametest 3, saving 2, gui 1, transfer 1)
     7  neoforge/gametest: neoforge/gametest/TestPorts.java  (saving 4, other 2, transfer 1)
     7  neoforge/gametest: neoforge/gametest/TestTanks.java  (saving 4, other 2, transfer 1)
     6  neoforge/gametest: neoforge/gametest/TransferPortEnergyGameTests.java  (gametest 4, saving 2)
     6  neoforge/gametest: neoforge/gametest/SecurityGameTests.java  (gametest 3, permissions 3)
     6  neoforge/gametest: neoforge/gametest/MenuDefinitionGameTests.java  (gametest 3, saving 2, gui 1)
     5  neoforge/gametest: neoforge/gametest/TransferPortCoreGameTests.java  (gametest 3, saving 2)
     5  neoforge/gametest: neoforge/gametest/MenuValuesGameTests.java  (gametest 3, saving 2)
     5  neoforge/gametest: neoforge/gametest/TransferPortTankGameTests.java  (gametest 3, saving 2)
     4  common/api: api/conduit/ConduitState.java  (saving 4)
     4  common/api: api/transfer/TransferPort.java  (saving 4)
     4  common/api: api/inventory/ItemSlots.java  (saving 4)
     4  common/api: api/inventory/ResourceTank.java  (saving 4)
     4  common/api: api/machine/RedstoneControl.java  (saving 4)
     4  common/main: conduit/ConduitRenderValue.java  (saving 4)
     4  common/main: conduit/SyncedConduitState.java  (saving 4)
     4  common/main: transfer/PortChannel.java  (saving 4)
     4  common/main: inventory/LibResourceTank.java  (saving 4)
     4  common/main: transfer/LibTransferPort.java  (saving 4)
     4  common/test: menu/MenuValueTableTest.java  (saving 4)
     4  common/test: api/transfer/TransferPortsTest.java  (saving 4)
     4  neoforge/gametest: neoforge/gametest/MachineRecipeGameTests.java  (gametest 3, components 1)
     4  neoforge/gametest: neoforge/gametest/RuntimeGameTests.java  (gametest 3, transfer 1)
     4  neoforge/gametest: neoforge/gametest/EnergyEdgeGameTests.java  (gametest 4)
     4  neoforge/gametest: neoforge/gametest/ConduitApiGameTests.java  (gametest 3, saving 1)
     4  neoforge/gametest: neoforge/gametest/ToolkitApiGameTests.java  (gametest 3, gui 1)
     3  common/main: resource/MachineItemStorage.java  (saving 2, transfer 1)
     3  neoforge/gametest: neoforge/gametest/ContentToggleGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/PresetGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/LibGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/EnergyDiagnosticsGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/LibLangGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/SideConfigGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/PipeShowcaseGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/ResourceKeyGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/RecipeTransferGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/FacadeRecipeGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/MenuActionGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/ResourceKindGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/SyncedListGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/GuideConfigGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/SideConfigFacingGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/EnergyConfigEnvironment.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/perf/PerfEnvironmentGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/perf/PerfEnvironment.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/TransferPortGameTests.java  (gametest 3)
     3  neoforge/gametest: neoforge/gametest/MenuGameTests.java  (gametest 3)
     2  neoforge/gametest: neoforge/gametest/perf/TransferPerfGameTests.java  (gametest 2)
     2  neoforge/gametest: neoforge/gametest/perf/ProfilerPerfGameTests.java  (gametest 2)
     2  neoforge/gametest: neoforge/gametest/perf/KeyRoutePerfGameTests.java  (gametest 2)
     2  neoforge/gametest: neoforge/gametest/perf/EnergyPerfGameTests.java  (gametest 2)
     2  neoforge/gametest: neoforge/gametest/perf/SchedulerPerfGameTests.java  (gametest 2)
     2  neoforge/gametest: neoforge/gametest/ProfilerGameTests.java  (gametest 2)
     1  neoforge/gametest: neoforge/gametest/PipeShowcase.java  (other 1)
     1  neoforge/main: neoforge/perf/PerfBudget.java  (gametest 1)
     1  neoforge/gametest: neoforge/gametest/GlowLookDemoClient.java  (rendering 1)
     1  common/api: api/resource/UnknownResource.java  (saving 1)
     1  common/api: api/transfer/PortSettings.java  (saving 1)
     1  common/test: api/transfer/PortSettingsTest.java  (saving 1)
     1  common/test: api/resource/KindKeyTest.java  (saving 1)
[other]
    15  common/main: conduit/ConduitBlock.java  (other 7, override 6, components 1, naming 1)
    11  common/main: facade/FacadeRecipe.java  (recipes 6, components 4, override 1)
    10  common/api: api/recipe/MachineRecipe.java  (recipes 6, override 4)
     8  common/api: api/recipe/MachineRecipeType.java  (recipes 7, other 1)
     7  common/main: content/RecipeContext.java  (recipes 5, override 2)
     5  common/api: api/recipe/MachineRecipeBuilder.java  (recipes 3, components 2)
     4  common/api: api/recipe/ItemOutput.java  (components 4)
     4  common/test: api/conduit/ConduitColoursTest.java  (other 4)
     4  common/main: conduit/ConduitAttachments.java  (other 4)
     3  common/api: api/conduit/ConduitColours.java  (other 3)
     3  common/api: api/security/OwnerSecurity.java  (permissions 3)
     3  common/test: client/conduit/ConduitGeometryTest.java  (other 3)
     2  common/main: client/conduit/ConduitGeometry.java  (other 2)
     2  neoforge/main: neoforge/guide/GuideWebExport.java  (other 2)
     2  neoforge/main: neoforge/client/MenuClient.java  (other 2)
     2  neoforge/main: neoforge/datagen/LibItemTagsProvider.java  (other 2)
     2  neoforge/main: neoforge/datagen/ContentItemTagsProvider.java  (other 2)
     2  neoforge/main: neoforge/datagen/LibRecipeProvider.java  (components 1, recipes 1)
     2  common/api: api/conduit/ConduitLook.java  (other 2)
     2  common/main: content/BlockBuilder.java  (naming 2)
     2  common/main: content/ConduitBuilder.java  (naming 2)
     2  common/main: content/ContentIndex.java  (naming 1, other 1)
     2  common/main: menu/ToolkitMenu.java  (other 2)
     1  neoforge/main: neoforge/guide/LibGuides.java  (other 1)
     1  neoforge/main: neoforge/compat/kubejs/MachineRecipeComponents.java  (components 1)
     1  neoforge/main: neoforge/platform/NeoForgeNetworkHelper.java  (network 1)
     1  neoforge/main: neoforge/datagen/LibDataGenerators.java  (other 1)
     1  neoforge/main: neoforge/datagen/ContentRecipeProvider.java  (recipes 1)
     1  neoforge/main: neoforge/datagen/ContentIndexProvider.java  (other 1)
     1  common/api: api/conduit/ConduitTypes.java  (naming 1)
     1  common/api: api/security/OwnerRecord.java  (other 1)
     1  common/api: api/recipe/MachineRecipeLookup.java  (other 1)
     1  common/api: api/carried/CarriedItems.java  (other 1)
     1  common/main: facade/FacadeContent.java  (naming 1)
     1  common/main: facade/Facades.java  (override 1)
     1  common/main: content/ItemBuilder.java  (naming 1)
     1  common/main: gui/transfer/CraftingGridMenus.java  (network 1)
     1  common/main: registry/ModBlockEntities.java  (other 1)
```
