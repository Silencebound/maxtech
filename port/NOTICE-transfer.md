# Vendored NeoForge transfer api (1.21.1 build only)

MaxTech Lib's 1.21.1 build contains a copy of the transfer api of NeoForge 26.1.2 (`net.neoforged.neoforge.transfer`,
NeoForge 26.1.2.109 sources), because NeoForge 21.1 has no transfer api and Lib's code is written against it.

- Copyright (c) NeoForged and contributors. Licence: **LGPL-2.1-only** (the SPDX line in each file); the licence text
  is NeoForge's, https://github.com/neoforged/NeoForge/blob/1.21.x/LICENSE.txt (LGPL 2.1).
- Location: `port/shim/lib/common/src/api/java/dev/silencebound/maxtech/lib/shim/transfer/**`, vendored by
  `port/tools/vendor_transfer.py`.
- Modifications: the package is relocated to `dev.silencebound.maxtech.lib.shim.transfer` (so it cannot clash with
  another mod's copy), Minecraft 26.1.2 names are mapped to 1.21.1 ones, a few vanilla hook classes are left out
  (listed in the script), and the code is adapted to Minecraft 1.21.1 and NeoForge 21.1. Each file says so below its
  licence header, as LGPL-2.1 section 2(a) asks.
- The vendored files stay under LGPL-2.1-only; they are not relicensed to Lib's licence. This file travels in the
  1.21.1 jar's META-INF next to Lib's licence texts.

Open question for the owner before any public 1.21.1 release: confirm that shipping LGPL-2.1-only files inside the
LGPL-3.0 Lib jar is acceptable as is (separate files, own notices), or ship them as a separate jar-in-jar library.
