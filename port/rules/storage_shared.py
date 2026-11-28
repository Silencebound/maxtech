"""Storage measurement (port/storage-errors.md): Lib's package-wide rules, applied to Storage's files too.

Lib's work packages scope their rules to Lib's own files (ownership.txt). The rules that hold for a whole package
rather than one file (gui's widget supertypes, saving's block entity IO, NBT getters and GameTest api) are general
26.1 -> 1.21.1 moves, so Storage gets copies of them here, rescoped to storage/**; Lib's modules stay untouched. A rule
counts as package-wide when it is scoped to at least MIN_FILES files; file-specific rules are not copied.
"""

import copy
import importlib.util
from pathlib import Path

from framework import ImportMove

_RULES = Path(__file__).resolve().parent
MIN_FILES = 8
STORAGE = ("storage/**/*.java",)
STORAGE_GAMETEST = ("storage/neoforge/src/gametest/java/**/*.java",)


def _load(name: str):
    spec = importlib.util.spec_from_file_location(f"port_storage_shared_{name}", _RULES / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _rescoped() -> list:
    rules = []
    for name in ("gui", "saving"):
        for rule in _load(name).RULES:
            if len(rule.files) < MIN_FILES:
                continue
            only_gametests = all("/src/gametest/" in f for f in rule.files)
            mine = copy.copy(rule)
            mine.files = STORAGE_GAMETEST if only_gametests else STORAGE
            mine.hits = 0
            mine.note = f"Lib's {name} rule, rescoped to Storage"
            rules.append(mine)
    return rules


_SHIM = "dev.silencebound.maxtech.lib.shim"

# Lib's shims of 26.1-only types that Lib's packages point at file by file; for Storage they are plain contract moves
# (candidates for core.py's CONTRACTS, port/storage-errors.md).
CONTRACTS = [
    ImportMove("net.minecraft.world.item.ItemStackTemplate", f"{_SHIM}.misc.ItemStackTemplate", files=STORAGE),
    ImportMove("com.mojang.math.Quadrant", f"{_SHIM}.client.render.Quadrant", files=STORAGE),
    ImportMove("net.minecraft.client.resources.model.cuboid.CuboidFace", f"{_SHIM}.client.render.CuboidFace",
               files=STORAGE),
    ImportMove("com.mojang.blaze3d.platform.Transparency", f"{_SHIM}.client.render.Transparency", files=STORAGE),
]

RULES = _rescoped() + CONTRACTS
