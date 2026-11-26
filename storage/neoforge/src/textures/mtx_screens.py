#!/usr/bin/env python3
"""Terminals and screens in the MaxTech family style 2a (MTX item 3, docs/milestones/MTX.md).

Terminal fronts port ``termTex2`` from the family style 2a page pixel
for pixel: the 2a base with the Storage accent line, a coloured screen at x and y 2 to 13 with a lighter glare row on
top, a darker left edge and a soft shadow along the bottom (no corner brackets), and big lighter tiles in one of the
page's layouts. The screen colour is the only thing a colour changes; the accent line stays Storage cyan.

Only fronts carry the accent line (owner 2026-10-17): sides, backs and tops are plain kit casing without it, and so is
the stock keeper's face, which shows on every side of a block without a facing. Thin faces
(display edges, the smart label's 1 px sides) are a flat frame fill, since a frame would not read on 1 to 2 px. Every
texture here is static.

Writes only its own ``OUTPUTS``; ``--check`` writes nothing and exits 1 if a committed PNG differs (``TextureKitTest``
runs it). ``--sheet FILE`` writes every layout in every screen colour instead.

Usage: python mtx_screens.py [--check] [--list] [--sheet FILE]
"""
import argparse
import sys

sys.dont_write_bytecode = True  # importing the kit must not leave __pycache__ in the source tree

from texture_kit import (  # noqa: E402
    ACCENTS, BEVEL_DARK, BEVEL_LIGHT, FRAME, FRAME2, N, SCREEN, TEXTURES, Canvas, base, casing, check, hex_, shade,
    write_png)

WHITE = (255, 255, 255)
# The page's screen colours (SCOL); dye variants would pick from these.
SCREEN_COLOURS = {
    "cyan": hex_("#2a8fa8"),
    "purple": hex_("#8a5aa8"),
    "teal": hex_("#3a9a8a"),
    "blue": hex_("#4a4ae0"),
    "orange": hex_("#e89a3a"),
    "green": hex_("#5ac83a"),
    "white": hex_("#c8c8c8"),
}
LAYOUTS = ("term", "craft2", "panel", "list", "grid", "recipe")


def mix(a, b, f):
    """The page's ``mix``; the canvas stores it rounded half to even (Uint8ClampedArray), as Python's round does."""
    return tuple(round(a[i] + (b[i] - a[i]) * f) for i in range(3))


def screen_face(layout, colour, line=True):
    """A terminal front: the 2a base, the coloured screen and the layout's tiles (the page's ``termTex2``); ``line``
    false for a block whose every face shows the screen."""
    c = Canvas()
    base(c, ACCENTS["storage"], "working", line=line)
    lt, dk = mix(colour, WHITE, 0.6), shade(colour, 0.55)
    c.rect(2, 2, 13, 13, colour)
    c.rect(2, 2, 13, 2, mix(colour, WHITE, 0.25))  # glare row along the top
    c.rect(2, 3, 2, 13, dk)  # darker left edge
    c.rect(3, 13, 13, 13, shade(colour, 0.75))  # soft shadow along the bottom

    def tile(x0, y0, w, h):
        c.rect(x0, y0, x0 + w - 1, y0 + h - 1, lt)

    if layout == "grid":
        for r in range(3):
            for col in range(3):
                tile(4 + col * 3, 4 + r * 3, 2, 2)
    elif layout == "term":
        # Search bar with its cursor, two rows of item tiles, the scrollbar with its thumb on top.
        tile(3, 4, 6, 1)
        c.put(9, 4, dk)
        c.put(10, 4, lt)
        for r in range(2):
            for col in range(3):
                tile(3 + col * 3, 7 + r * 3, 2, 2)
        for y in range(7, 12):
            c.put(12, y, lt if y <= 8 else dk)
    elif layout == "panel":
        tile(4, 4, 5, 5)
        c.rect(5, 5, 7, 7, colour)
        tile(10, 4, 2, 5)
        tile(4, 10, 8, 2)
    elif layout == "list":
        tile(4, 4, 6, 2)
        for y in (7, 9, 11):
            tile(4, y, 8 - (y % 3), 1)
    elif layout == "recipe":
        # Three inputs, an arrow, one output: a pattern is a recipe (owner 2026-10-18, so it reads apart from AE2's
        # encoding terminal).
        for y in (4, 7, 10):
            tile(3, y, 2, 2)
        c.rect(5, 7, 6, 8, dk)
        c.rect(7, 6, 7, 9, dk)
        c.rect(8, 7, 8, 8, dk)
        tile(10, 5, 3, 6)
        c.rect(11, 7, 11, 8, colour)
    elif layout == "craft2":
        for r in range(3):
            for col in range(3):
                tile(4 + col * 3, 3 + r * 3, 2, 2)
        tile(4, 12, 8, 1)
    else:
        raise ValueError("unknown layout " + layout)
    return c


def plain_casing():
    return casing(ACCENTS["storage"], line=False)


def frame_fill():
    """Flat frame with the kit's speckle, for faces only 1 or 2 px thick."""
    c = Canvas()
    for y in range(N):
        for x in range(N):
            c.put(x, y, FRAME2 if (x * 3 + y * 5) % 7 == 0 else FRAME)
    return c


def display_glass():
    """The display panel's glass, edge to edge so a wall of panels reads as one screen: dark with a faint pixel grid.
    The block entity renderer draws the picture over it."""
    c = Canvas(fill=SCREEN + (255,))
    dot = shade(SCREEN, 1.35)
    for y in range(1, N, 4):
        for x in range(1, N, 4):
            c.put(x, y, dot)
    return c


def display_part_screen():
    """The display part's front: the 2a base around a dark screen with the terminal's glare, edge and shadow."""
    c = Canvas()
    base(c, ACCENTS["storage"], "working", line=True)
    c.rect(2, 2, 13, 13, SCREEN)
    c.rect(2, 2, 13, 2, mix(SCREEN, WHITE, 0.12))
    c.rect(2, 3, 2, 13, shade(SCREEN, 0.7))
    return c


def display_frame():
    """The display's frame strips are 2 px wide and take their front from this texture's edges: frame and bevel, and
    the accent line on the bottom strip."""
    c = Canvas()
    base(c, ACCENTS["storage"], "working", line=True)
    return c


def smart_label_front():
    """The label's 12 x 8 plate (x 2 to 13, y 4 to 11 of the texture): a small 2a panel around a dark screen for the
    renderer's text, with a 4 px idle accent line."""
    c = frame_fill()
    x0, y0, x1, y1 = 2, 4, 13, 11
    c.rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, SCREEN)
    c.rect(x0 + 1, y1 - 1, x1 - 1, y1 - 1, BEVEL_DARK)  # bevel, as the kit's: dark bottom and right,
    c.rect(x1 - 1, y0 + 1, x1 - 1, y1 - 1, BEVEL_DARK)
    c.rect(x0 + 1, y0 + 1, x1 - 1, y0 + 1, BEVEL_LIGHT)  # light top and left
    c.rect(x0 + 1, y0 + 1, x0 + 1, y1 - 1, BEVEL_LIGHT)
    line = shade(ACCENTS["storage"], 0.45)
    for x in range(6, 10):
        c.put(x, y1, line)
    return c


# Paths relative to textures/. Screen colours follow the page's scene: storage terminals cyan (the crafting terminal with
# the 3 x 3 grid layout), pattern and request teal (owner 2026-10-18, away from AE2's purple); research blue and stock
# keeper green so the blocks tell apart.
OUTPUTS = (
    ("block/terminal/screen", lambda: screen_face("term", SCREEN_COLOURS["cyan"])),
    ("block/terminal/bezel", plain_casing),
    ("block/pattern_terminal_front", lambda: screen_face("recipe", SCREEN_COLOURS["teal"])),
    ("block/crafting_terminal_front", lambda: screen_face("craft2", SCREEN_COLOURS["cyan"])),
    ("block/research_terminal_front", lambda: screen_face("grid", SCREEN_COLOURS["blue"])),
    ("block/request_board_front", lambda: screen_face("list", SCREEN_COLOURS["teal"])),
    ("block/stock_keeper_front", lambda: screen_face("list", SCREEN_COLOURS["green"], line=False)),
    ("block/display/screen", display_glass),
    ("block/display/part_screen", display_part_screen),
    ("block/display/frame", display_frame),
    ("block/display/edge", frame_fill),
    ("block/display/side", plain_casing),
    ("block/smart_label/front", smart_label_front),
    ("block/smart_label/side", frame_fill),
)


def outputs():
    return [(path, make()) for path, make in OUTPUTS]


def sheet(path, sc=8):
    gap = 4
    cell = N * sc + gap
    out = Canvas(len(SCREEN_COLOURS) * cell, len(LAYOUTS) * cell, (58, 60, 66, 255))
    for row, layout in enumerate(LAYOUTS):
        for col, colour in enumerate(SCREEN_COLOURS.values()):
            t = screen_face(layout, colour)
            for y in range(N * sc):
                for x in range(N * sc):
                    p = t.get(x // sc, y // sc)
                    out.put(col * cell + x, row * cell + y, p[:3], p[3])
    write_png(path, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed PNGs, write nothing")
    ap.add_argument("--sheet", help="write every layout in every screen colour to this file instead")
    ap.add_argument("--list", action="store_true", help="print the texture paths this script owns, write nothing")
    args = ap.parse_args()
    items = outputs()
    if args.list:
        for item in items:
            print(item[0])
        return 0
    if args.check:
        bad = check(items)
        for path in bad:
            print("differs from mtx_screens.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_screens.py")
            return 1
        print(len(items), "screen textures match")
        return 0
    if args.sheet:
        sheet(args.sheet)
        return 0
    for path, c in items:
        write_png(TEXTURES / (path + ".png"), c)
    print(len(items), "screen textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
