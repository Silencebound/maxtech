"""Rules of work package `rendering`: rendering and model datagen (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.

Files whose 1.21.1 shape differs throughout (the conduit and facade models, the submit renderers, the face preview,
model datagen) are overrides in port/override/lib/...; the rules below cover the files that only need 26.1 types
pointed at the shims in shim/client/render.
"""

from framework import ImportMove, Regex, Rename, Transform  # noqa: F401

_SHIM = "dev.silencebound.maxtech.lib.shim.client.render"
_NEO = "lib/neoforge/src"
_CLIENT = f"{_NEO}/main/java/dev/silencebound/maxtech/lib/neoforge/client"
_CLIENT_TEST = f"{_NEO}/test/java/dev/silencebound/maxtech/lib/neoforge/client"
_DATAGEN = f"{_NEO}/main/java/dev/silencebound/maxtech/lib/neoforge/datagen"

_UVS = (f"{_CLIENT}/ConduitFaceUvs.java", f"{_CLIENT_TEST}/ConduitFaceUvsTest.java")
_LAYERS = (f"{_CLIENT}/ConduitLayers.java", f"{_CLIENT_TEST}/ConduitLayersTest.java")


def _cut(start: str, end: str, replacement: str = ""):
    """A Transform body removing the text from `start` up to (not including) `end`; fails loudly if either is gone."""

    def fn(text: str, rel: str) -> str:
        i = text.find(start)
        j = text.find(end, i + 1)
        if i < 0 or j < 0:
            raise SystemExit(f"rendering rule: {rel} no longer has {start!r} .. {end!r}; re-check the rule")
        return text[:i] + replacement + text[j:]

    return fn


_MODEL_IMPORTS = ("net.minecraft.client.color.item.ItemTintSource", "net.minecraft.client.data.models",
                  "net.minecraft.client.resources.model.sprite.Material",
                  "net.neoforged.neoforge.client.model.generators", "net.neoforged.neoforge.client.model.item",
                  "dev.silencebound.maxtech.lib.neoforge.client.ConduitModel",
                  "dev.silencebound.maxtech.lib.api.conduit.ConduitLook", "java.util.EnumMap",
                  "java.util.HashMap", "java.util.Objects", "java.util.Optional", "java.util.function.Predicate",
                  "net.minecraft.core.Direction", "dev.silencebound.maxtech.lib.content.ModelLayer",
                  "dev.silencebound.maxtech.lib.content.BlockModel", "dev.silencebound.maxtech.lib.content.ItemModel")

RULES = [
    # Face UVs and turns: same shapes as 26.1's, backed by 1.21.1's BlockFaceUV.
    ImportMove("com.mojang.math.Quadrant", f"{_SHIM}.Quadrant", files=_UVS),
    ImportMove("net.minecraft.client.resources.model.cuboid.CuboidFace", f"{_SHIM}.CuboidFace", files=_UVS),
    Regex(r"FaceInfo\.fromFacing\(face\)\.getVertexInfo\(vertex\)\.select\(from, to\)",
          "CuboidFace.select(FaceInfo.fromFacing(face).getVertexInfo(vertex), from, to)", files=_UVS,
          note="1.21.1's FaceInfo.VertexInfo has no select"),
    # 1.21.1 lines take their width from the render type's line state, not from each vertex.
    Regex(r"\.setLineWidth\(width\)", "", files=(f"{_CLIENT}/BoxLines.java",)),
    Regex(r", int colour, float width\)", ", int colour)", files=(f"{_CLIENT}/BoxLines.java",)),
    Regex(r", colour, width\)", ", colour)", files=(f"{_CLIENT}/BoxLines.java",)),
    # Chunk layers by sprite transparency.
    ImportMove("com.mojang.blaze3d.platform.Transparency", f"{_SHIM}.Transparency", files=_LAYERS),
    # 1.21.1's BakedQuad has no material flags; ConduitModel (override) keeps materialFlags with its own constant.
    Regex(r"import net\.minecraft\.client\.resources\.model\.geometry\.BakedQuad;\n", "",
          files=(f"{_CLIENT_TEST}/ConduitModelHooksTest.java",)),
    Rename("BakedQuad", "ConduitModel", files=(f"{_CLIENT_TEST}/ConduitModelHooksTest.java",)),
    # Model datagen: ContentDatagen keeps its providers, language, tags and recipes; the 26.1 model generation moves
    # into ContentModelProvider (override), written for 1.21.1's BlockStateProvider.
    Transform(_cut("    /**\n     * Writes the declared block and item models;", "    static void language("),
              name="drop 26.1 model generation", files=(f"{_DATAGEN}/ContentDatagen.java",)),
    Transform(_cut("    private static void simpleBlock(", "    /**\n     * The data providers for one mod's content."),
              name="drop 26.1 model helpers", files=(f"{_DATAGEN}/ContentDatagen.java",)),
    Transform(_cut("    /** The texture slots of a generated item model;", "    private ContentDatagen() {"),
              name="drop 26.1 item layer slots", files=(f"{_DATAGEN}/ContentDatagen.java",)),
    *[Regex(rf"import {m.replace('.', chr(92) + '.')}[\w.]*;\n", "", files=(f"{_DATAGEN}/ContentDatagen.java",))
      for m in _MODEL_IMPORTS],
    # 1.21.1 has one GatherDataEvent for client and server data.
    Regex(r"GatherDataEvent\.Client\b", "GatherDataEvent", files=(f"{_DATAGEN}/ContentDatagen.java",)),
]
