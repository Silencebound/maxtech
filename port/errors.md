# Error map: Lib on 1.21.1

State of 2026-11-28, branch `port/1.21.1` after the transfer, rendering, gui and saving ports and the first skeleton
fix, with `port/tools/errormap.sh`. It runs one javac per work package over that package's own files (the rest comes
from the sourcepath), so one package's errors never mask another's; `--single` is the old all-in-one run (the two
differ only when a package's missing types stop javac before it attributes the bodies: the saving package once saw 75
errors in the single run and about 480 compiled alone). Regenerate with `port/tools/errormap.sh > port/build/em.txt`;
`port/build/errormap.log` holds every message.

**110 errors in 36 files** (phase 1, before any package work: 1060 in 185 files; the spike: 1490 in 227). Both modes
give 110 today. Still a lower bound once new shims make more types resolve. Error counts are not effort: the transfer
bridge has few errors but the most design work.

| package | errors | files with errors | files owned (ownership.txt) |
|---|---:|---:|---:|
| transfer | 78 | 17 | 94 |
| rendering | 0 | 0 | 29 |
| gui | 5 | 1 | 51 |
| saving + GameTest | 5 | 4 | 85 |
| other | 22 | 14 | 446 |

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
| gui | `port/rules/gui.py` | `common/src/api/java/dev/silencebound/maxtech/lib/shim/client/gui/**` (`GuiGraphicsExtractor`, input events; also `neoforge/src/main/java/.../shim/client/gui/**`) | `port/override/<its files>` |
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
total 110 errors in 36 files (per package, own files only)

by area:
  transfer        43 errors   17 files
  other           41 errors   19 files
  override        14 errors    7 files
  recipes          8 errors    4 files
  components       2 errors    1 files
  permissions      1 errors    1 files
  naming           1 errors    1 files
by package (file owner):
  transfer        78 errors   17 files
  rendering        0 errors    0 files
  gui              5 errors    1 files
  saving           5 errors    4 files
  other           22 errors   14 files

[transfer]
    22  common/api: shim/transfer/item/ResourceHandlerSlot.java  (transfer 9, override 8, other 5)
    13  common/api: shim/transfer/fluid/FluidResource.java  (transfer 10, other 2, override 1)
    12  common/api: shim/transfer/item/ItemResource.java  (transfer 6, other 3, components 2, override 1)
     7  common/api: shim/transfer/item/PlayerInventoryWrapper.java  (other 6, override 1)
     3  common/api: shim/transfer/resource/RegisteredResource.java  (other 3)
     3  common/api: shim/transfer/item/ItemStackResourceHandler.java  (other 2, transfer 1)
     3  common/api: shim/transfer/item/VanillaContainerWrapper.java  (transfer 2, override 1)
     2  common/api: shim/transfer/access/ItemAccess.java  (other 1, transfer 1)
     2  common/api: shim/transfer/fluid/FluidStacksResourceHandler.java  (transfer 1, other 1)
     2  common/api: shim/transfer/item/ItemUtil.java  (transfer 2)
     2  common/api: shim/transfer/item/ItemStacksResourceHandler.java  (transfer 1, other 1)
     2  common/main: inventory/MachineMoves.java  (transfer 2)
     1  common/api: shim/transfer/access/StackItemAccess.java  (override 1)
     1  common/api: shim/transfer/fluid/FluidUtil.java  (other 1)
     1  common/api: shim/transfer/fluid/BucketResourceHandler.java  (transfer 1)
     1  common/api: shim/transfer/fluid/ItemAccessFluidHandler.java  (transfer 1)
     1  common/api: shim/transfer/item/ItemAccessItemHandler.java  (transfer 1)
[rendering]
[gui]
     5  neoforge/main: neoforge/client/LayoutCheckClient.java  (other 5)
[saving]
     2  common/main: inventory/LibItemSlots.java  (transfer 2)
     1  common/main: resource/MachineItemStorage.java  (transfer 1)
     1  neoforge/gametest: neoforge/gametest/ResourceKeyGameTests.java  (transfer 1)
     1  neoforge/gametest: neoforge/gametest/InventoryGameTests.java  (transfer 1)
[other]
     4  neoforge/main: neoforge/compat/kubejs/MachineRecipeComponents.java  (recipes 4)
     3  neoforge/main: neoforge/datagen/ContentIndexProvider.java  (other 2, naming 1)
     2  common/main: menu/ToolkitMenu.java  (other 2)
     2  neoforge/main: neoforge/compat/kubejs/LibKubeJSPlugin.java  (recipes 2)
     2  neoforge/main: neoforge/platform/NeoForgeLifecycleEvents.java  (other 2)
     1  neoforge/main: neoforge/guide/LibGuides.java  (other 1)
     1  neoforge/main: neoforge/MaxtechLibNeoForge.java  (other 1)
     1  neoforge/main: neoforge/ContentEnabledCondition.java  (other 1)
     1  neoforge/main: neoforge/LibCommands.java  (permissions 1)
     1  neoforge/main: neoforge/client/AccentDemoCommand.java  (other 1)
     1  neoforge/main: neoforge/datagen/LibRecipeProvider.java  (recipes 1)
     1  neoforge/main: neoforge/datagen/ContentFluidTagsProvider.java  (other 1)
     1  neoforge/main: neoforge/datagen/ContentBlockTagsProvider.java  (override 1)
     1  neoforge/main: neoforge/datagen/ContentRecipeProvider.java  (recipes 1)
```
