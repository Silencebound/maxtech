"""Core rules, applied before every package's rules: mechanical 26.1.2 -> 1.21.1 renames that hold everywhere.

Owner: the port skeleton (phase 1). Work packages add their rules to their own module (port/errors.md, "Ownership"),
not here; a rename every package needs goes here through one small, separate commit.
"""

import re

from framework import ImportMove, Regex, Rename, Transform, map_parts

# The vendored transfer api lives in Lib's shim layer (port/shim, NOTICE-transfer.md), relocated so it can never clash
# with another mod's copy or a later NeoForge.
TRANSFER = ImportMove("net.neoforged.neoforge.transfer", "dev.silencebound.maxtech.lib.shim.transfer",
                      note="vendored NeoForge transfer api")

# 26.1 renamed ResourceLocation to Identifier (and everything named after it).
NAMING = [
    Rename("IdentifierArgument", "ResourceLocationArgument"),
    Rename("IdentifierException", "ResourceLocationException"),
    Rename("Identifier", "ResourceLocation"),
    Rename("writeIdentifier", "writeResourceLocation"),
    Rename("readIdentifier", "readResourceLocation"),
    # ResourceKey.identifier() is location() on 1.21.1. Lib declares no identifier() method of its own.
    Regex(r"\.identifier\(\)", ".location()"),
]

# Packages that moved between 1.21.1 and 26.1.2 with the same classes inside.
MOVES = [
    ImportMove("net.minecraft.util.Util", "net.minecraft.Util"),
    ImportMove("net.minecraft.advancements.criterion", "net.minecraft.advancements.critereon"),
    # NeoForge's model data is a client package on 1.21.1.
    ImportMove("net.neoforged.neoforge.model.data", "net.neoforged.neoforge.client.model.data"),
    # 26.1 renamed ClickType to ContainerInput (same constants).
    ImportMove("net.minecraft.world.inventory.ContainerInput", "net.minecraft.world.inventory.ClickType"),
    Rename("ContainerInput", "ClickType"),
    # 26.1's Level.recipeAccess() is getRecipeManager() on 1.21.1.
    Regex(r"\.recipeAccess\(\)", ".getRecipeManager()"),
    # 1.21.1 has one GatherDataEvent for client and server data.
    Regex(r"GatherDataEvent\.Client\b", "GatherDataEvent"),
]

# Contract moves: 26.1 types that 1.21.1 lacks point at a Lib shim of the same name and shape, which the named work
# package writes under port/shim (port/errors.md). They live here, not in the package's rules, because they touch files
# of every package; until the shim class exists the error just names the shim type instead of the vanilla one.
_SHIM = "dev.silencebound.maxtech.lib.shim"
CONTRACTS = [
    # transfer: 26.1's Capabilities.Item/Fluid/Energy (ResourceHandler/EnergyHandler capabilities) as Lib's own
    # capabilities bridged to 1.21.1's IItemHandler/IFluidHandler/IEnergyStorage.
    ImportMove("net.neoforged.neoforge.capabilities.Capabilities", f"{_SHIM}.transfer.bridge.Capabilities"),
    # saving
    *[ImportMove(f"net.minecraft.world.level.storage.{n}", f"{_SHIM}.saving.{n}")
      for n in ("ValueInput", "ValueOutput", "TagValueInput", "TagValueOutput")],
    ImportMove("net.neoforged.neoforge.common.util.ValueIOSerializable", f"{_SHIM}.saving.ValueIOSerializable"),
    # saving (GameTests)
    *[ImportMove(f"net.minecraft.gametest.framework.{n}", f"{_SHIM}.gametest.{n}")
      for n in ("TestData", "FunctionGameTestInstance", "TestEnvironmentDefinition", "GameTestException")],
    # gui
    ImportMove("net.minecraft.client.gui.GuiGraphicsExtractor", f"{_SHIM}.client.gui.GuiGraphicsExtractor"),
    *[ImportMove(f"net.minecraft.client.input.{n}", f"{_SHIM}.client.gui.input.{n}")
      for n in ("MouseButtonEvent", "MouseButtonInfo", "InputWithModifiers", "KeyEvent", "CharacterEvent")],
    # other
    ImportMove("net.minecraft.util.ARGB", f"{_SHIM}.misc.ARGB"),
]

_UNNAMED =re.compile(r"(?<![\w$])_(?![\w$])")


def unnamed_variables(text: str, rel: str) -> str:
    """Java 21 has no unnamed variables (`_`, final in 22); each becomes unused<N>, numbered per file so no two clash.

    `_` is a keyword since Java 9, so a lone `_` token in code is always an unnamed variable or pattern.
    """
    counter = iter(range(1_000_000))
    return map_parts(text, ("code",), lambda t: _UNNAMED.sub(lambda m: f"unused{next(counter)}", t))


JAVA21 = [Transform(unnamed_variables, name="unnamed variables -> unused<N>")]

RULES = [TRANSFER, *NAMING, *MOVES, *CONTRACTS, *JAVA21]
