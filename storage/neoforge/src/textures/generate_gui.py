#!/usr/bin/env python3
"""Draws Storage's GUI sprites with generate.py's palette and helpers: the terminal's side tab icons (grid, crafting
jobs, network log, stock keepers, pattern overview, order list, export, network history). They are 16 x 16 like item icons, light grey parts with a dark outline and one cyan
accent each, so they read on the terminal's dark buttons and on the teal hover. The view button's icons (all, items,
fluids) are 10 x 10 in the same style, to fit the 14 x 14 buttons next to the search box. Output lands under
``textures/gui/sprites/terminal/``, which the GUI atlas stitches (sprite id ``maxtech_storage:terminal/<name>``).

Usage: python generate_gui.py [--out DIR] [--sheet DIR]"""
import argparse
import math
from pathlib import Path

import generate as g
from generate import Img, P, mix


def shade_edges(a, c_hi=None, c_lo=None):
    """Lights the top and left edge of every base-coloured part and darkens its bottom and right edge."""
    c_hi = c_hi or P["hi"]
    c_lo = c_lo or P["lo"]
    src = list(a.px)

    def at(x, y):
        return src[y * a.w + x] if 0 <= x < a.w and 0 <= y < a.h else None

    for y in range(a.h):
        for x in range(a.w):
            p = at(x, y)
            if p is None or p[:3] != P["base"]:
                continue
            if at(x + 1, y) is None or at(x, y + 1) is None:
                a.set(x, y, c_lo)
            elif at(x - 1, y) is None or at(x, y - 1) is None:
                a.set(x, y, c_hi)
    return a


def tab_grid():
    """The terminal's grid: a casing frame around a 3 x 3 field of slots, two of them holding a lit item."""
    a = Img()
    a.rect(1, 1, 14, 14, P["base"])
    shade_edges(a)
    for row in range(3):
        for col in range(3):
            x, y = 3 + col * 4, 3 + row * 4
            a.rect(x, y, x + 1, y + 1, P["inset"])
    a.rect(3, 3, 4, 4, P["acc"])
    a.rect(11, 7, 12, 8, P["acc"])
    a.set(3, 3, P["glow"])
    a.set(11, 7, P["glow"])
    return a.outline()


def tab_jobs():
    """Crafting jobs: a gear with a cyan hub."""
    a = Img()
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            ax, ay = abs(dx), abs(dy)
            cardinal = min(ax, ay) <= 1.5 and r <= 7
            diagonal = abs(ax - ay) <= 1 and r <= 6.4
            if r <= 4.8 or cardinal or diagonal:
                a.set(x, y, P["base"])
    shade_edges(a)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - cx, y - cy)
            if r <= 2.4:
                a.set(x, y, P["inset"])
    a.rect(7, 7, 8, 8, P["acc"])
    a.set(7, 7, P["glow"])
    return a.outline()


def tab_log():
    """The network log: a sheet with four entry lines, each with a cyan bullet."""
    a = Img()
    a.rect(2, 1, 13, 14, P["base"])
    shade_edges(a)
    for i, end in enumerate((11, 9, 11, 8)):
        y = 3 + i * 3
        a.set(4, y, P["acc"])
        a.rect(6, y, end, y, P["inset"])
    return a.outline()


def tab_stock():
    """Stock keepers: a crate with a cyan up-arrow on its face (kept topped up)."""
    a = Img()
    a.rect(1, 2, 14, 14, P["base"])
    shade_edges(a)
    a.rect(3, 4, 12, 4, P["inset"])
    a.rect(3, 6, 12, 12, P["inset"])
    a.rect(7, 8, 8, 12, P["acc"])
    a.rect(5, 9, 10, 9, P["acc"])
    a.rect(6, 8, 9, 8, P["acc"])
    a.rect(7, 7, 8, 7, P["glow"])
    return a.outline()


def tab_patterns():
    """Pattern overview: two stacked pattern sheets, the front one with a cyan grid mark."""
    a = Img()
    a.rect(4, 1, 14, 11, P["base"])
    a.rect(1, 4, 11, 14, P["base"])
    shade_edges(a)
    a.rect(3, 6, 9, 12, P["inset"])
    for (x, y) in ((4, 7), (7, 7), (4, 10), (7, 10)):
        a.rect(x, y, x + 1, y + 1, P["acc"])
    a.set(4, 7, P["glow"])
    return a.outline()


def tab_order():
    """Order list: a clipboard with three ticked lines (many items, one job)."""
    a = Img()
    a.rect(2, 2, 13, 14, P["base"])
    shade_edges(a)
    a.rect(6, 1, 9, 2, P["inset"])
    for i in range(3):
        y = 5 + i * 3
        a.set(4, y, P["acc"])
        a.rect(6, y, 11, y, P["inset"])
    a.set(4, 5, P["glow"])
    return a.outline()


def tab_export():
    """Export: a list sheet with a cyan arrow leaving it to the right (the list goes out as text, a book or a display)."""
    a = Img()
    a.rect(1, 2, 10, 14, P["base"])
    shade_edges(a)
    for i in range(3):
        y = 5 + i * 3
        a.rect(3, y, 7, y, P["inset"])
    a.rect(8, 7, 13, 8, P["acc"])
    for i in range(3):
        a.rect(12 - i, 5 + i, 12 - i, 10 - i, P["acc"])
    a.set(14, 7, P["acc"])
    a.set(14, 8, P["acc"])
    a.set(13, 7, P["glow"])
    return a.outline()


def tab_history():
    """Network history: a panel with a bar graph, the newest bar cyan (the controller's telemetry, ledger and alarms)."""
    a = Img()
    a.rect(1, 1, 14, 14, P["base"])
    shade_edges(a)
    a.rect(3, 3, 12, 12, P["inset"])
    for x, top in ((4, 9), (6, 7), (8, 10), (10, 5)):
        a.rect(x, top, x + 1, 11, P["base"])
    a.rect(10, 5, 11, 11, P["acc"])
    a.set(10, 5, P["glow"])
    return a.outline()


def _crate(a, x0, y0, x1, y1):
    """A small light grey item box with a dark slot in the middle (the items of the view button)."""
    a.rect(x0, y0, x1, y1, P["base"])
    shade_edges(a)
    a.rect(x0 + 2, y0 + 2, x1 - 2, y1 - 2, P["inset"])


def _drop(a, cx, top):
    """A cyan droplet 4 wide and 6 high whose tip is at (cx, top) and (cx + 1, top), with a glint (the fluids)."""
    for dy, half in enumerate((0, 0, 1, 1, 1, 0)):
        a.rect(cx - half, top + dy, cx + 1 + half, top + dy, P["acc"])
    a.set(cx - 1, top + 3, P["glow"])


def view_items():
    """Only items: a box with a dark slot."""
    a = Img(10, 10)
    _crate(a, 1, 1, 8, 8)
    a.set(4, 4, P["acc"])
    a.set(5, 5, P["acc"])
    return a.outline()


def view_fluids():
    """Only fluids: a droplet."""
    a = Img(10, 10)
    _drop(a, 4, 2)
    return a.outline()


def view_all():
    """Items and fluids: a box with a droplet in front of its lower right corner."""
    a = Img(10, 10)
    _crate(a, 1, 1, 6, 6)
    _drop(a, 6, 3)
    for y in range(2, 10):
        for x in range(4, 10):
            p = a.get(x, y)
            # A dark rim where the droplet overlaps the box, so the two shapes stay apart.
            if p is not None and p[:3] != P["acc"] and p[:3] != P["glow"] and (
                    any(a.get(x + dx, y + dy) is not None and a.get(x + dx, y + dy)[:3] in (P["acc"], P["glow"])
                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))):
                a.set(x, y, mix(P["inset"], -0.3))
    return a.outline()


def outputs():
    return {
        "gui/sprites/terminal/tab_grid": tab_grid(),
        "gui/sprites/terminal/tab_jobs": tab_jobs(),
        "gui/sprites/terminal/tab_log": tab_log(),
        "gui/sprites/terminal/tab_stock": tab_stock(),
        "gui/sprites/terminal/tab_patterns": tab_patterns(),
        "gui/sprites/terminal/tab_order": tab_order(),
        "gui/sprites/terminal/tab_export": tab_export(),
        "gui/sprites/terminal/tab_history": tab_history(),
        "gui/sprites/terminal/view_all": view_all(),
        "gui/sprites/terminal/view_items": view_items(),
        "gui/sprites/terminal/view_fluids": view_fluids(),
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(g.DEFAULT_OUT))
    ap.add_argument("--sheet", help="directory for a contact sheet")
    args = ap.parse_args()
    out = outputs()
    g.write(Path(args.out), out)
    if args.sheet:
        sd = Path(args.sheet)
        sd.mkdir(parents=True, exist_ok=True)
        g.sheet(out, sd / "gui-sheet.png", cols=4)
    print(len(out), "sprites")
