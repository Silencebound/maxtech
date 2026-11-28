#!/usr/bin/env python3
"""Vendors NeoForge 26.1.2's transfer api into Lib's 1.21.1 shim layer (port/NOTICE-transfer.md).

    python3 -I port/tools/vendor_transfer.py <neoforge-26.1.2.x-sources.jar>

The sources jar is in the Gradle cache after any main build, e.g.
~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/26.1.2.109/*/neoforge-26.1.2.109-sources.jar.

Each file keeps NeoForge's licence header (LGPL-2.1-only) and gets a modification notice below it. The package moves
from net.neoforged.neoforge.transfer to dev.silencebound.maxtech.lib.shim.transfer, and the core rules
(port/rules/core.py) run once over the copy. Later hand edits for 1.21.1 are made in the vendored files themselves and
belong to the transfer work package; re-running this script overwrites them, so diff before committing a re-vendor.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PORT / "rules"))
import core  # noqa: E402

PREFIX = "net/neoforged/neoforge/transfer/"
DEST = PORT / "shim/lib/common/src/api/java/dev/silencebound/maxtech/lib/shim/transfer"
# Vanilla hooks and wrappers NeoForge patches into the game itself; Lib never uses them and 1.21.1 has its own hooks.
SKIP = {
    "item/VanillaInventoryCodeHooks.java",
    "item/ComposterWrapper.java",
    "fluid/CauldronWrapper.java",
    "fluid/DispenseFluidContainer.java",
    "item/BundleItemHandler.java",
    "item/LivingEntityEquipmentWrapper.java",
}
NOTICE = ("/*\n * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package\n"
          " * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.\n */\n")


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    jar = Path(sys.argv[1])
    written = 0
    with zipfile.ZipFile(jar) as z:
        for name in sorted(z.namelist()):
            if not name.startswith(PREFIX) or not name.endswith(".java"):
                continue
            rel = name[len(PREFIX):]
            if rel in SKIP:
                continue
            text = z.read(name).decode("utf-8")
            for rule in core.RULES:
                text = rule.apply(text, rel)
            end = text.index("*/") + 3 if text.startswith("/*") else 0
            text = text[:end] + NOTICE + text[end:]
            out = DEST / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text, encoding="utf-8")
            written += 1
    print(f"vendored {written} files from {jar.name} to {DEST.relative_to(PORT.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
