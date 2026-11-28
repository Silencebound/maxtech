# Vendored NeoForge transfer api (1.21.1 build only)

MaxTech Lib's 1.21.1 build contains a copy of the transfer api of NeoForge 26.1.2 (`net.neoforged.neoforge.transfer`,
NeoForge 26.1.2.109 sources), because NeoForge 21.1 has no transfer api and Lib's code is written against it.

- Copyright (c) NeoForged and contributors. Licence: **LGPL-2.1-only** (the SPDX line in each file); the licence text
  is NeoForge's, https://github.com/neoforged/NeoForge/blob/1.21.x/LICENSE.txt (LGPL 2.1).
- Location: `port/shim/lib/common/src/api/java/dev/silencebound/maxtech/lib/shim/transfer/**`, vendored by
  `port/tools/vendor_transfer.py`, except `bridge/legacy/`, which is Lib's own code under Lib's licence.
- Also vendored, by hand, into `shim/transfer/port/` and `shim/transfer/bridge/`: NeoForge 26.1.2's `StackCopySlot`,
  the legacy adapters `ItemResourceHandlerAdapter`, `FluidResourceHandlerAdapter`,
  `FluidResourceHandlerItemAdapter` and `EnergyHandlerAdapter`, and the resource-handler part of `Capabilities`.
  `port/TypedInstance.java` is a stand-in for Minecraft 26.1's `TypedInstance`, written for this build and kept under
  the same licence as the files around it.
- Modifications: the package is relocated to `dev.silencebound.maxtech.lib.shim.transfer` (so it cannot clash with
  another mod's copy), Minecraft 26.1.2 names are mapped to 1.21.1 ones, a few vanilla hook classes are left out
  (listed in the script), and the code is adapted to Minecraft 1.21.1 and NeoForge 21.1. Each file says so below its
  licence header, as LGPL-2.1 section 2(a) asks.
- The vendored files stay under LGPL-2.1-only; they are not relicensed to Lib's licence. This file travels in the
  1.21.1 jar's META-INF next to Lib's licence texts.

Decided (owner 2026-11-28): the vendored api ships as its own nested jar (jar-in-jar) under LGPL-2.1, apart from
Lib's classes. Lib's 1.21.1 jar carries it as `META-INF/jarjar/dev.silencebound.maxtech.lib.maxtech_lib-transfer-<version>.jar`
(built by `transferJar` in `port/projects/lib-common`), with this file and the LGPL-2.1 text (`LICENSE-transfer.txt`)
in its META-INF. `bridge/legacy/` stays in Lib's jar.

The nested jar uses Lib's classes at runtime and carries no copy of them: the `shim.saving` interfaces (`ValueInput`,
`ValueOutput`, `ValueIOSerializable`) and, from `EnergyHandlerUtil`, `bridge.legacy.LegacyForeignLast`. Licence: the
vendored code only calls Lib's classes, so no LGPL-3.0 code lands in the
LGPL-2.1 jar and nothing is relicensed; each jar can be replaced on its own. Classloading: the nested jar is a game
library (`FMLModType: GAMELIBRARY`), loaded in the same layer as Lib, where both see each other; a copy would put the
`shim.saving` package into two jars, which the module layer refuses (split package), or need a second package that
Lib's implementations would then have to implement. `:lib:neoforge:checkTransferJar` (part of `check`) fails when
the two jars share a package.
