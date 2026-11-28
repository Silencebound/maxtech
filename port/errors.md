# Error map: Lib on 1.21.1

State of 2026-11-28, branch `port/1.21.1` after the five package ports and the compile gate (`port/1.21.1-compile`).
**Lib compiles on 1.21.1**: 0 errors in every source set (common api/main/test, neoforge api/main/gametest/test),
with `port/tools/errormap.sh` and with `(cd port && ./gradlew compileJava compileTestJava compileGametestJava)`
(phase 1, before any package work: 1060 errors in 185 files; the spike: 1490 in 227). The error map stays the tool
for main's next changes: it runs one javac per work package over that package's own files (the rest from the
sourcepath), so one package's errors never mask another's; `--single` is the all-in-one run.

| package | errors | files with errors | files owned (ownership.txt) |
|---|---:|---:|---:|
| transfer | 0 | 0 | 94 |
| rendering | 0 | 0 | 29 |
| gui | 0 | 0 | 51 |
| saving + GameTest | 0 | 0 | 85 |
| other | 0 | 0 | 446 |

## Tests (2026-11-28)

- **Unit tests** (`./gradlew test`): all pass. Common, plain JUnit: 752 run, 0 failed, 12 skipped (legacy bridge
  tests that need FML). Neoforge, inside FML: 89 run, 0 failed, 0 skipped (its 40 plus common's 49 legacy-bridge
  tests, the 12 above among them).
- **Jar**: `checkTransferJar` fails: `shim.transfer.bridge` is in both jars (the vendored `Capabilities` in the
  nested one, Lib's `ForeignKinds`, added by the transfer package's foreign-last change, in Lib's). FML refuses a
  package split over two modules, so the built jar cannot load until `ForeignKinds` moves to a Lib-only package
  (for example `bridge.legacy`). Dev runs and tests are not affected (one mod there).
- **GameTests** (`./gradlew :lib:neoforge:runGameTestServer`): 198 ran, 173 passed, 25 failed. By cause:
  - *Jade and mock players* (20: `menu_action_*` 2, `menu_definition_opens`, `side_config_*` 7, `toolkit_api_*` 10):
    `GameTestHelper.makeMockServerPlayerInLevel` runs `PlayerList.placeNewPlayer`, where Jade (runtimeOnly in dev
    runs) sends `jade:server_ping_v1` to a connection that never negotiated it, and NeoForge throws. Fix in the
    GameTest shim (a mock player that skips the login event) or keep Jade out of the GameTest run.
  - *Resources in 26.1 formats* (4: `content_toggle_recipe_condition`, `facade_recipe_binder`,
    `machine_recipes_refused`, `machine_recipes_demo_loads`): recipe JSON writes ingredients as strings
    (`"#c:ingots/iron"`, `"minecraft:dead_bush"`); 1.21.1 wants `{"tag": ...}`/`{"item": ...}` objects. Affects
    Lib's generated recipes and the GameTest data packs.
  - *Behaviour* (1: `machine_recipes_round_trip`): 1.21.1's ingredient stream codec sends a tag ingredient as its
    items, so the streamed recipe has `{"item": ...}` where the test expects `{"tag": ...}`.
  - *Structures in 26.1 NBT*: none failed.
- Not failing but in 26.1 formats: the pipe showcase's text displays (`Display entityNot a string`: 26.1 text
  component NBT in the summoned entity data).

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
4. **Licence**: the vendored transfer api is LGPL-2.1-only; it ships as its own nested jar in Lib's jar (owner
   2026-11-28; port/NOTICE-transfer.md has the layout and why it uses Lib's `shim.saving` interfaces at runtime).
5. **Resources** are copied unchanged (no rules on JSON yet): 26.1 item model definitions (`assets/*/items/*.json`),
   recipe and tag JSON formats may need rules or datagen on 1.21.1; that only shows at runtime, belongs to rendering
   (models) and other (recipes/tags), and is not in the counts above.

## Errors by package and file

Generated by `port/tools/errormap.py`.

```
total 0 errors in 0 files (per package, own files only)
```
