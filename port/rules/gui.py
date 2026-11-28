"""Rules of work package `gui`: screens, widgets and input (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.

The 26.1 widget and screen API (extract*, MouseButtonEvent, KeyEvent, onPress(InputWithModifiers), ...) is kept as it
is: Lib's widgets and screens extend the gui shim's bridge classes (shim/client/gui/Extract*.java), which present
26.1's methods on top of 1.21.1's AbstractWidget, Screen and AbstractContainerScreen. So the rules here only swap
supertypes and fix the few calls into vanilla that 1.21.1 names differently.
"""

from pathlib import Path

from framework import ImportMove, Regex, Rename, Transform  # noqa: F401

_SHIM = "dev.silencebound.maxtech.lib.shim.client.gui"


def _owned() -> tuple[str, ...]:
    lines = (Path(__file__).resolve().parents[1] / "ownership.txt").read_text(encoding="utf-8").splitlines()
    return tuple(parts[1] for parts in (line.split() for line in lines)
                 if len(parts) == 2 and parts[0] == "gui" and parts[1].endswith(".java"))


OWNED = _owned()
_LIB = "lib/common/src/main/java/dev/silencebound/maxtech/lib"
_API = "lib/common/src/api/java/dev/silencebound/maxtech/lib/api"


def _only(*paths: str) -> tuple[str, ...]:
    return tuple(paths)


# The 26.1 API on 1.21.1's widget and screen classes (the shim's bridge classes).
SUPERTYPES = [
    Regex(r"\bextends AbstractWidget\b(?!\.)", f"extends {_SHIM}.ExtractWidget", files=OWNED),
    Regex(r"\bextends AbstractButton\b", f"extends {_SHIM}.ExtractButton", files=OWNED),
    Regex(r"\bextends Screen\b", f"extends {_SHIM}.ExtractScreen", files=OWNED),
    Regex(r"\bextends AbstractContainerScreen<", f"extends {_SHIM}.ExtractContainerScreen<", files=OWNED),
    # 26.1 made the click sound a static of AbstractWidget.
    Regex(r"\bAbstractWidget\.playButtonClickSound\(", f"{_SHIM}.ExtractWidget.playButtonClickSound(", files=OWNED),
    # 1.21.1 has no render pipelines; the extractor takes 26.1's GUI pipelines as markers.
    ImportMove("net.minecraft.client.renderer.RenderPipelines", f"{_SHIM}.RenderPipelines", files=OWNED),
]

# ScreenComponents.TintedButton extends vanilla's Button, which keeps 1.21.1's renderWidget: its 26.1 extractContents
# becomes renderWidget over the extractor, and the default label is 1.21.1's (the same scrolling centred string).
TINTED_BUTTON = [
    Regex(r"protected void extractContents\(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a\) \{"
          r"(?=\s*graphics\.blitSprite\(RenderPipelines\.GUI_TEXTURED, SPRITES\.get\()",
          "protected void renderWidget(net.minecraft.client.gui.GuiGraphics vanillaGraphics, int mouseX, int mouseY,"
          " float a) {\n            GuiGraphicsExtractor graphics = GuiGraphicsExtractor.of(vanillaGraphics);",
          files=_only(f"{_LIB}/client/gui/ScreenComponents.java")),
    Regex(r"extractDefaultLabel\(graphics\.textRendererForWidget\(this, GuiGraphicsExtractor\.HoveredTextEffects\.NONE\)\);",
          "renderString(vanillaGraphics, net.minecraft.client.Minecraft.getInstance().font,"
          " getFGColor() | net.minecraft.util.Mth.ceil(alpha * 255.0F) << 24);",
          files=_only(f"{_LIB}/client/gui/ScreenComponents.java")),
]

# Vanilla calls 1.21.1 names differently.
VANILLA = [
    # 26.1 renamed the toast manager.
    Regex(r"\.getToastManager\(\)", ".getToasts()", files=_only(f"{_API}/client/gui/AccentToast.java")),
    # 1.21.1's container screen getters are NeoForge's getGuiLeft/getGuiTop/getXSize/getYSize.
    *[Regex(rf"\bcontainer\.{old}\(\)", f"container.{new}()",
            files=_only("lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/client/LayoutCheckClient.java"))
      for old, new in (("getLeftPos", "getGuiLeft"), ("getTopPos", "getGuiTop"), ("getImageWidth", "getXSize"),
                       ("getImageHeight", "getYSize"))],
]

# Jade 15 (1.21.1) builds tooltip rows from IElement, not LayoutElement (InfoElements is an override for the rest).
_JADE = "lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/compat/jade"
JADE = [
    ImportMove("net.minecraft.client.gui.layouts.LayoutElement", "snownee.jade.api.ui.IElement",
               files=_only(f"{_JADE}/BlockInfoComponentProvider.java")),
    Rename("LayoutElement", "IElement", files=_only(f"{_JADE}/BlockInfoComponentProvider.java")),
]

RULES = [*SUPERTYPES, *TINTED_BUTTON, *VANILLA, *JADE]
