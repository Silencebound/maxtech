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
    # 1.21.1's item cooldowns are per item; 26.1's per stack (its cooldown group, the item for Lib's scanner).
    Regex(r"getCooldowns\(\)\.(isOnCooldown|addCooldown)\(stack\b", r"getCooldowns().\1(stack.getItem()",
          files=_only(f"{_LIB}/info/ScannerItem.java")),
    # 1.21.1's appendHoverText adds to a list and has no TooltipDisplay.
    Regex(r"appendHoverText\(ItemStack stack, TooltipContext context, TooltipDisplay display,\s*"
          r"Consumer<Component> builder, TooltipFlag flag\) \{",
          "appendHoverText(ItemStack stack, TooltipContext context, java.util.List<Component> tooltip,"
          " TooltipFlag flag) {\n        Consumer<Component> builder = tooltip::add;",
          files=_only(f"{_LIB}/info/ScannerItem.java", f"{_LIB}/facade/FacadeItem.java")),
    Regex(r"import net\.minecraft\.world\.item\.component\.TooltipDisplay;\n", "",
          files=_only(f"{_LIB}/info/ScannerItem.java", f"{_LIB}/facade/FacadeItem.java")),
    # 26.1 renamed ClickType to ContainerInput.
    ImportMove("net.minecraft.world.inventory.ContainerInput", "net.minecraft.world.inventory.ClickType",
               files=_only(f"{_LIB}/menu/MenuTestSession.java")),
    Rename("ContainerInput", "ClickType", files=_only(f"{_LIB}/menu/MenuTestSession.java")),
]

RULES = [*SUPERTYPES, *TINTED_BUTTON, *VANILLA]
