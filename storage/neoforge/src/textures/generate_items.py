#!/usr/bin/env python3
"""Draws Storage's item textures in a vanilla-like style (owner decision 2026-10-15, item-redraw mockup).

Rules, as in the mockup: silhouette first; light from the top left; every shape is shaded from a 5-shade, hue-shifted
ramp (outline, dark, mid, light, highlight; cooler shadows, warmer highlights); the outline is the darkest shade of the
item's own ramp, never black, while small parts in a second material (pins, legs, flanges) keep their own colours;
symmetric objects are symmetric left to right. Owner picks: processors are the square chip (body 3..12, centred die,
gold pins at 4, 6, 9, 11 on all four sides; tiers differ by the ring round the die), wires are spools, the tantalum
capacitor is an electrolytic can and the circuit board has a gold trace grid with a centred chip.

A drawing is a 16 x 16 map of (ramp, shade) pixels; ``Sprite.outline`` turns the edge of the listed ramps into their
darkest shade, so shapes are drawn full size and the outline eats their border. Generic materials (dusts, ingots,
gems, raw tantalite) are tinted vanilla textures and have no texture here; cable items are 3D models.

``--check-symmetry`` writes nothing and exits 1 if an icon on the ``SYMMETRIC`` list is not mirror-symmetric left to
right: the silhouette, which ramp each pixel uses and where the darkest shade (outlines, cut symbols) sits must
mirror; the other shades may differ, since light comes from the top left. ``TextureKitTest`` runs it; it needs no
Pillow.

Usage: python generate_items.py [--out DIR] [--sheet FILE] [--check-symmetry]
Needs Pillow to write or draw a sheet. Output defaults to Storage's item textures next to this script's repository
root.
"""
import argparse
import math
import sys
from pathlib import Path

N = 16
ROOT = Path(__file__).resolve().parents[3]
DEFAULT_OUT = ROOT / "common/src/main/resources/assets/maxtech_storage/textures/item"


def hx(h):
    return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)


# Outline, dark, mid, light, highlight. The first rows are the mockup's; the rest follow the same rule.
RAMPS = {
    "lumen": ["#1d4a5a", "#2f8aa6", "#56c4dd", "#9be8f2", "#effcf6"],
    "ember": ["#5a1f12", "#a8421c", "#e2762c", "#f7ae52", "#fff0b8"],
    "data": ["#1a2a6a", "#2f4fb8", "#4f86e8", "#8ec0f7", "#e6f6ff"],
    "void": ["#14081f", "#341850", "#5a2f86", "#8c5ec0", "#d8c2f2"],
    "niobium": ["#2a2f45", "#5a6488", "#8a96b8", "#bcc6dc", "#f2f4f6"],
    "tantalum": ["#23262e", "#4a505e", "#727a8a", "#a4abb8", "#e8ecef"],
    "signal": ["#4a1414", "#9a2e22", "#d4563a", "#f08a5a", "#ffd8a8"],
    "silicon": ["#1f2630", "#3e4a5a", "#64748a", "#97a8bc", "#dde8f2"],
    "copper": ["#4a2216", "#8e4a2c", "#c26f45", "#e69a6a", "#ffd2b0"],
    "rock": ["#2a1f1f", "#4f3a36", "#7a5a52", "#a3847a", "#d2b8aa"],
    "board": ["#0f2a1a", "#1e5a32", "#2f8a4a", "#58b870", "#b8f0c0"],
    "steel": ["#262a30", "#4a505a", "#737a86", "#a2a9b4", "#e6eaee"],
    "gold": ["#5a3a10", "#a8721e", "#e0a830", "#f5d468", "#fff6c8"],
    "graphite": ["#101318", "#20262e", "#2f3742", "#46505e", "#6c7886"],
    "memory": ["#0f3a22", "#23703f", "#3ea85e", "#76d48a", "#d2f6d6"],
    "amber": ["#4a2a08", "#92561a", "#d08a2a", "#f0bc5a", "#fff0c0"],
    "indigo": ["#1c1a4a", "#3a3a8e", "#5a62c4", "#8c96e6", "#d8dcff"],
    "glass": ["#2a4a52", "#5a8a96", "#8ec4cc", "#c4ecf0", "#f4ffff"],
    "paper": ["#4a3e2e", "#a8987a", "#d8ccb0", "#efe6d0", "#fffaf0"],
    "blueprint": ["#0e1f4a", "#1f3c88", "#2f5cb8", "#5c8ce0", "#d0e4ff"],
    "leather": ["#2a1410", "#5a2a1e", "#86422c", "#b0643e", "#e0a070"],
    "red": ["#3a0e0e", "#8a2020", "#c83a32", "#ec6a58", "#ffc0a8"],
    "orange": ["#4a2408", "#a0560e", "#e08a1c", "#f8b848", "#fff0b0"],
    "green": ["#123a14", "#2a7a2e", "#46b04a", "#86dc7a", "#e0ffd0"],
    "screen": ["#081218", "#10222c", "#1a3442", "#2a4e60", "#4a7a8c"],
}
RGB = {k: [hx(c) for c in v] for k, v in RAMPS.items()}
FAMILIES = {"logic": "lumen", "memory": "memory", "signal": "amber", "io": "indigo"}


def noise(x, y, seed=0):
    """A stable 0..1 value per pixel (the mockup's hash)."""
    return ((((x + seed) * 73856093) ^ (y * 19349663)) & 0xFFFFFFFF) % 1000 / 1000


def quant(k):
    return 5 if k > 0.82 else 4 if k > 0.6 else 3 if k > 0.36 else 2


class Sprite:
    def __init__(self):
        self.p = [[None] * N for _ in range(N)]

    def set(self, x, y, ramp, shade):
        if 0 <= x < N and 0 <= y < N:
            self.p[y][x] = (ramp, max(1, min(5, shade)))

    def get(self, x, y):
        return self.p[y][x] if 0 <= x < N and 0 <= y < N else None

    def clear(self, x, y):
        if 0 <= x < N and 0 <= y < N:
            self.p[y][x] = None

    def rect(self, x0, y0, x1, y1, ramp, shade):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, ramp, shade(x, y) if callable(shade) else shade)

    def body(self, x0, y0, x1, y1, ramp, sym=True):
        """A box lit from the top left: light top ring, dark bottom ring (and right ring unless ``sym``)."""

        def shade(x, y):
            if y == y0 + 1 and x == x0 + 1:
                return 5
            if y == y0 + 1:
                return 4
            if y == y1 - 1:
                return 2
            if not sym and x == x0 + 1:
                return 4
            if not sym and x == x1 - 1:
                return 2
            return 3

        self.rect(x0, y0, x1, y1, ramp, shade)

    def ellipse(self, cx, cy, rx, ry, ramp, shade):
        for y in range(N):
            for x in range(N):
                dx, dy = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
                if dx * dx + dy * dy <= 1:
                    self.set(x, y, ramp, shade(x, y, dx, dy) if callable(shade) else shade)

    def poly(self, pts, ramp, shade):
        for y in range(N):
            for x in range(N):
                if inside(x + 0.5, y + 0.5, pts):
                    self.set(x, y, ramp, shade(x, y) if callable(shade) else shade)

    def line(self, x0, y0, x1, y1, ramp, shade):
        steps = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(steps + 1):
            t = i / steps if steps else 0
            self.set(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), ramp, shade)

    def mirror(self):
        """Copies the left half onto the right, so a symmetric object stays exactly symmetric."""
        for y in range(N):
            for x in range(N // 2):
                self.p[y][N - 1 - x] = self.p[y][x]
        return self

    def outline(self, *ramps):
        """The edge pixels of ``ramps`` (all when none are named) become their ramp's darkest shade."""
        edge = []
        for y in range(N):
            for x in range(N):
                v = self.p[y][x]
                if v is None or (ramps and v[0] not in ramps):
                    continue
                if any(self.get(x + dx, y + dy) is None for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    edge.append((x, y, v[0]))
        for x, y, ramp in edge:
            self.p[y][x] = (ramp, 1)
        return self

    def image(self):
        from PIL import Image

        im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        for y in range(N):
            for x in range(N):
                v = self.p[y][x]
                if v is not None:
                    im.putpixel((x, y), RGB[v[0]][v[1] - 1] + (255,))
        return im


def inside(x, y, pts):
    c = False
    j = len(pts) - 1
    for i in range(len(pts)):
        (xi, yi), (xj, yj) = pts[i], pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


# ---------------------------------------------------------------------------------------------------------- chips


PINS = (4, 6, 9, 11)


def chip(die, ring=None, body="steel"):
    """The square chip (owner pick A): body 3..12, a centred die in ``die``'s colours, gold pins on all four sides."""
    s = Sprite()
    s.rect(3, 3, 12, 12, body, lambda x, y: 4 if y == 3 else 2 if y == 12 else 3 if y < 6 else 2)
    for p in PINS:
        for k in (1, 2):
            s.set(p, k, "gold", 4 if k == 1 else 3)
            s.set(p, 15 - k, "gold", 2 if k == 1 else 3)
            s.set(k, p, "gold", 4 if k == 1 else 3)
            s.set(15 - k, p, "gold", 3 if k == 1 else 2)
    s.outline(body)
    # The die: a ring (the tier) round a lit square in the family's colours.
    for i in range(5, 11):
        for j in (5, 10):
            s.set(i, j, ring or body, 1 if ring is None else (4 if j == 5 else 2))
            s.set(j, i, ring or body, 1 if ring is None else (4 if j == 5 else 2))
    s.rect(6, 6, 9, 9, die, lambda x, y: 5 if (x, y) == (6, 6) else 4 if y == 6 or x == 6 else 3 if y < 9 else 2)
    return s


def processor(family, tier):
    """Basic, advanced and elite processors: the family's die; advanced a gold ring, elite a data crystal ring."""
    ring = {"basic": None, "advanced": "gold", "elite": "data"}[tier]
    return chip(FAMILIES[family], ring, "steel" if tier == "basic" else "graphite")


def controller_chip(kind):
    """The item and fluid controller chips: the square chip on a graphite body with a symbol in its die."""
    s = chip("screen", "lumen", "graphite")
    s.rect(6, 6, 9, 9, "screen", 2)
    if kind == "item":
        s.rect(6, 6, 9, 9, "lumen", 3)
        s.rect(6, 6, 9, 6, "lumen", 5)
        s.rect(6, 7, 6, 9, "lumen", 4)
        s.rect(9, 7, 9, 9, "lumen", 2)
    else:
        for x, y, v in ((7, 6, 4), (8, 6, 4), (6, 7, 4), (7, 7, 5), (8, 7, 3), (9, 7, 3), (6, 8, 3), (7, 8, 3),
                        (8, 8, 3), (9, 8, 2), (7, 9, 2), (8, 9, 2)):
            s.set(x, y, "data", v)
    return s


def die(family, tier):
    """A die (mockup): a cut grid of cells, the centre cell in the family's colours; advanced dies carry gold pads at
    the corners, elite dies are cut from data crystal."""
    base = "data" if tier == "elite" else "silicon"
    s = Sprite()
    # Cut lines dark, each 2 x 2 cell lit at its top left.
    s.rect(3, 3, 12, 12, base, lambda x, y: 2 if x % 3 == 0 or y % 3 == 0 else 4 if x % 3 == 1 and y % 3 == 1
           else 3)
    s.outline(base)
    for i in range(4, 12):
        s.set(i, 4, base, 4 if i % 3 else 5)
    fam = FAMILIES[family]
    s.rect(7, 7, 8, 8, fam, 3)
    s.set(7, 7, fam, 5)
    s.set(8, 7, fam, 4)
    s.set(7, 8, fam, 4)
    if tier == "advanced":
        for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
            s.set(x, y, "gold", 4 if y == 4 else 3)
    return s


def mask(family):
    """A family mask: a steel frame round a glass plate with the family's pattern."""
    s = Sprite()
    s.body(2, 2, 13, 13, "steel")
    s.outline("steel")
    s.rect(4, 4, 11, 11, "glass", lambda x, y: 4 if y == 4 else 2 if y == 11 else 3)
    s.set(4, 4, "glass", 5)
    for x in range(4, 12):
        s.set(x, 3, "steel", 1)
        s.set(x, 12, "steel", 1)
    for y in range(4, 12):
        s.set(3, y, "steel", 1)
        s.set(12, y, "steel", 1)
    fam = FAMILIES[family]
    pattern = {
        "logic": [(x0 + dx, y0 + dy) for x0, y0 in ((5, 5), (9, 5), (7, 7), (5, 9), (9, 9)) for dx in (0, 1)
                  for dy in (0, 1)],
        "memory": [(x, y) for y in (5, 7, 9) for x in range(5, 11) if x not in (7, 8) or y != 7],
        "signal": [(5, 8), (6, 7), (7, 6), (8, 6), (9, 7), (10, 8), (5, 9), (10, 9), (6, 10), (9, 10)],
        "io": [(x, y) for x, y in ((7, 5), (8, 5), (6, 6), (9, 6), (7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8),
                                   (7, 9), (8, 9), (6, 9), (9, 9), (7, 10), (8, 10))],
    }[family]
    for x, y in pattern:
        s.set(x, y, fam, 1)
    return s


# ------------------------------------------------------------------------------------------------- wafers, silicon


def wafer(ramp, specks=None, grid=None):
    """A wafer (mockup): a disc with a flat at the bottom and a sheen across it; doped wafers are speckled with the
    dopant, advanced ones carry a gold die grid."""
    s = Sprite()
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            if dx * dx + dy * dy > 42 or dy > 5.6:
                continue
            streak = abs(dx + dy + 2) < 1.2
            s.set(x, y, ramp, 5 if streak else quant(0.55 - dx * 0.03 - dy * 0.04))
    s.outline(ramp)
    if grid:
        for y in range(N):
            for x in range(N):
                v = s.get(x, y)
                inner = abs(x + 0.5 - 8) < 5 and abs(y + 0.5 - 8) < 5
                if v and v[1] > 1 and (x in (5, 10) or y in (5, 10)) and inner:
                    s.set(x, y, grid, 3 if (x + y) % 2 else 4)
    if specks:
        for y in range(N):
            for x in range(N):
                v = s.get(x, y)
                if v and v[0] != grid and 1 < v[1] < 5 and noise(x, y, 7) < (0.12 if grid else 0.2):
                    s.set(x, y, specks, 3 if noise(y, x, 3) < 0.5 else 4)
    return s


def silicon():
    """Silicon (mockup): three prisms of a crystal cluster, lit from the left."""
    s = Sprite()
    prisms = [[(3, 13), (6, 4), (9, 5), (7, 14)], [(7, 14), (9, 5), (11, 3), (12, 13)],
              [(10, 13.5), (12, 7), (14.5, 8), (13.5, 14)]]
    for i, pts in enumerate(prisms):
        s.poly(pts, "silicon", lambda x, y, i=i: [4, 3, 2][i] + (1 if x == round(5 + i * 3) else 0))
    return s.outline()


def blend(ramp, specks):
    """A blend (mockup's dust): mounds of one powder speckled with the second one mixed into it."""
    s = Sprite()
    mounds = [(8, 10.5, 4.6, 3.2), (4.8, 11.5, 2.4, 1.8), (11.6, 11.6, 2.6, 1.9), (7.5, 7.8, 2.2, 1.6)]
    for y in range(N):
        for x in range(N):
            h, nx, ny = 0, 0, 0
            for cx, cy, rx, ry in mounds:
                dx, dy = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
                d = 1 - dx * dx - dy * dy
                if d > h:
                    h, nx, ny = d, dx, dy
            if h > 0:
                light = 0.55 - nx * 0.32 - ny * 0.42 + (0.25 if noise(x + 3, y) < 0.18 else 0)
                s.set(x, y, ramp, quant(light))
    for x, y in ((2, 9), (13, 8), (14, 13), (1, 13), (6, 14)):
        s.set(x, y, ramp, 4 if noise(x, 3) < 0.5 else 3)
    s.outline(ramp)
    for y in range(N):
        for x in range(N):
            v = s.get(x, y)
            if v and v[1] > 1 and noise(x * 3, y * 7) < 0.3:
                s.set(x, y, specks, v[1])
    return s


# ---------------------------------------------------------------------------------------------------- components


def spool(winding, flange="steel"):
    """A wire spool (owner pick A): windings between two flanges, symmetric left to right."""
    s = Sprite()
    s.rect(4, 4, 11, 11, winding, lambda x, y: 5 if y == 4 else 2 if y == 11 else 3 if y % 2 else 4)
    s.outline(winding)
    for x in (2, 3, 12, 13):
        for y in range(2, 14):
            s.set(x, y, flange, 2 if x in (2, 13) else 3)
        s.set(x, 2, flange, 4)
        s.set(x, 13, flange, 1)
    for y in range(2, 14):
        s.set(2, y, flange, 1)
        s.set(13, y, flange, 1)
    for x in (3, 12):
        s.set(x, 7, flange, 1)
        s.set(x, 8, flange, 1)
    return s


def capacitor():
    """The tantalum capacitor (owner pick B): an electrolytic can with a vent cross and side stripes on two legs."""
    s = Sprite()
    # The can: rounded top corners, a lit lid, a dark band near the bottom and darker stripes down the sides.
    s.rect(4, 1, 11, 11, "gold", lambda x, y: 4 if y <= 4 else 2 if x in (5, 10) or y == 9 else 3)
    s.clear(4, 1)
    s.clear(11, 1)
    s.outline("gold")
    # The vent cross stamped into the lid.
    for x in range(5, 11):
        s.set(x, 3, "gold", 2)
    for y in range(2, 5):
        s.set(7, y, "gold", 2)
        s.set(8, y, "gold", 2)
    s.set(5, 2, "gold", 5)
    for x in (6, 9):
        for y in range(12, 15):
            s.set(x, y, "steel", 4 if y == 12 else 3)
    return s


def circuit_board():
    """The circuit board (owner pick A): green board, a gold trace grid with pads, a centred chip."""
    s = Sprite()
    s.rect(1, 2, 14, 13, "board", lambda x, y: 4 if y == 2 else 2 if y == 13 else 3)
    s.outline("board")
    for x in range(2, 14):
        s.set(x, 5, "gold", 3)
        s.set(x, 10, "gold", 3)
    for y in range(3, 13):
        s.set(4, y, "gold", 3)
        s.set(11, y, "gold", 3)
    for x, y in ((4, 5), (11, 5), (4, 10), (11, 10), (2, 5), (13, 5), (2, 10), (13, 10)):
        s.set(x, y, "gold", 5)
    s.rect(6, 6, 9, 9, "graphite", lambda x, y: 3 if y == 6 else 2)
    s.set(6, 6, "graphite", 4)
    for x in (6, 9):
        s.set(x, 5, "gold", 4)
        s.set(x, 10, "gold", 4)
    return s


def plate(ramp, glass=False):
    """A plate (mockup) lying at an angle: a lit top, a front edge and a side edge; the casing plate has four rivet
    holes, display glass a sheen across it."""
    s = Sprite()
    # The top face, rows 2 to 11, and the plate's front edge below it, rows 12 and 13.
    s.rect(1, 2, 14, 13, ramp, lambda x, y: 2 if y >= 12 else 4 if y == 3 or x == 2 else 3)
    s.set(2, 3, ramp, 5)
    s.outline(ramp)
    for x in range(2, 14):
        s.set(x, 11, ramp, 2)
    if glass:
        for i in range(4):
            s.set(4 + i, 9 - i, ramp, 5)
            s.set(5 + i, 9 - i, ramp, 4)
            s.set(9 + i, 9 - i, ramp, 5)
    else:
        for x, y in ((4, 4), (11, 4), (4, 9), (11, 9)):
            s.set(x, y, ramp, 1)
            s.set(x, y + 1, ramp, 5)
    return s


def oscillator():
    """A crystal oscillator: a stamped steel can with a lumen quartz window on two legs."""
    s = Sprite()
    s.rect(3, 3, 12, 11, "steel", lambda x, y: 5 if y == 3 and x < 8 else 4 if y == 3 else 2 if y == 11 else 3)
    s.outline("steel")
    for y in (4, 10):
        for x in range(4, 12):
            s.set(x, y, "steel", 4 if y == 4 else 2)
    s.rect(6, 6, 9, 8, "lumen", lambda x, y: 4 if y == 6 else 3)
    s.set(6, 6, "lumen", 5)
    for x in (5, 10):
        for y in range(12, 15):
            s.set(x, y, "steel", 4 if y == 12 else 3)
    return s


# ---------------------------------------------------------------------------------------------------------- cells


CELL_COLOURS = {"small": "memory", "medium": "lumen", "large": "indigo", "bulk": "amber", "portable": "signal"}


def cell(size, filled):
    """A storage cell or its empty casing: a steel case with the size's colour on its top; a cell shows its lit core
    through the window, a casing an empty dark slot. The portable cell is shorter, with a grip."""
    s = Sprite()
    top = 4 if size == "portable" else 1
    s.body(3, top, 12, 14, "steel")
    s.outline("steel")
    colour = CELL_COLOURS[size]
    for x in range(4, 12):
        s.set(x, top + 1, colour, 4 if x < 6 else 3)
        s.set(x, top + 2, colour, 2)
    wy0, wy1 = top + 4, 12
    s.rect(5, wy0, 10, wy1, "graphite", 1)
    s.rect(6, wy0 + 1, 9, wy1 - 1, "graphite", lambda x, y: 2 if y == wy0 + 1 else 3)
    if filled:
        s.rect(6, wy0 + 1, 9, wy1 - 1, "lumen", lambda x, y: 4 if y == wy0 + 1 else 3)
        s.set(6, wy0 + 1, "lumen", 5)
        s.set(9, wy1 - 1, "lumen", 2)
    if size == "portable":
        for x in range(5, 11):
            s.set(x, 2, "graphite", 3 if x in (5, 10) else 1)
        s.set(5, 3, "graphite", 2)
        s.set(10, 3, "graphite", 2)
    return s


def memory_module(n):
    """A memory module: a green board with gold contacts along the bottom and one to four chips; tiers 5 and 6 carry
    void chips."""
    s = Sprite()
    s.rect(1, 4, 14, 11, "board", lambda x, y: 4 if y == 4 else 2 if y == 11 else 3)
    s.outline("board")
    for x in (2, 4, 6, 9, 11, 13):
        s.set(x, 10, "gold", 4)
        s.set(x, 11, "gold", 3)
    s.set(7, 10, "board", 1)
    s.set(8, 10, "board", 1)
    chip_ramp = "void" if n > 4 else "graphite"
    count = n if n <= 4 else n - 2
    slots = {1: [7], 2: [4, 10], 3: [3, 7, 11], 4: [2, 5, 9, 12]}[count]
    for x0 in slots:
        s.rect(x0, 5, x0 + 1, 8, chip_ramp, lambda x, y, x0=x0: 4 if y == 5 else 1 if y == 8 else 2 if x == x0 + 1
               else 3)
    return s


# ---------------------------------------------------------------------------------------------------- cards, modules


def card(body, symbol, accent):
    """An upgrade card: a slab in ``body``'s colours, gold contacts along the bottom, ``symbol`` drawn in ``accent`` on
    a dark window."""
    s = Sprite()
    s.body(2, 2, 13, 13, body)
    s.outline(body)
    s.rect(4, 4, 11, 10, "screen", lambda x, y: 1 if y == 4 or x in (4, 11) else 2)
    for x in PINS:
        s.set(x, 12, "gold", 4)
        s.set(x, 13, "gold", 3)
    for x, y, v in SYMBOLS[symbol]:
        s.set(x, y, accent, v)
    return s


def module(base, symbol):
    """A module: a graphite block with a crystal of ``base`` set into its face and ``symbol`` cut dark into it."""
    s = Sprite()
    s.body(2, 2, 13, 13, "graphite")
    s.outline("graphite")
    s.poly([(8, 2.5), (13.5, 8), (8, 13.5), (2.5, 8)], base, lambda x, y: 4 if x + y < 15 else 2 if x + y > 16 else 3)
    s.set(7, 4, base, 5)
    for x, y, v in SYMBOLS[symbol]:
        s.set(x, y, base, 1)
    return s


def _pts(rows, x0, y0, v=4):
    return [(x0 + x, y0 + y, v) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == "#"]


SYMBOLS = {
    "speed": _pts(["#..#..", ".#..#.", "..#..#", ".#..#.", "#..#.."], 5, 5),
    "crafting": _pts(["##..##", "##..##", "......", "##..##", "##..##"], 5, 5),
    "blueprint": _pts(["######", "#..#.#", "####.#", "#....#", "######"], 5, 5, 3),
    "partition": _pts(["##..##", "##..##", "##..##", "##..##", "##..##"], 5, 5),
    "read_only": _pts([".##.", "#..#", "####", "#..#", "####"], 6, 5),
    "void": _pts([".##.", "#..#", "#..#", ".##."], 6, 6),
    # The modules' symbols are 6 px wide at x 5 to 10, so they mirror about the item's centre like the diamond.
    "spd": _pts(["..##..", ".####.", "#.##.#", "..##..", "..##.."], 5, 5, 5),
    "eff": _pts(["..##..", "..##..", "######", "######", "..##..", "..##.."], 5, 5, 1),
    "vd": _pts(["#....#", ".#..#.", "..##..", "..##..", ".#..#.", "#....#"], 5, 5, 5),
    "prec": _pts([".####.", "#....#", "#.##.#", "#.##.#", "#....#", ".####."], 5, 5, 5),
    # Controller upgrade cards (M7R item 2): a double chevron up (larger batches), a lightning bolt (less FE) and a
    # battery with its bottom kept full (the surge reserve).
    "batch": _pts(["..##..", ".#..#.", "#....#", "..##..", ".#..#."], 5, 5),
    "bolt": _pts(["...##", "..##.", ".####", ".##..", "##..."], 6, 5),
    "reserve": _pts([".##.", "#..#", "#..#", "####", "####"], 6, 5),
    # The view filter (B09 item 24): a funnel, 6 px wide at x 5 to 10 so it mirrors about the centre.
    "funnel": _pts(["######", ".####.", "..##..", "..##..", "..##.."], 5, 5),
}


def void_lattice():
    """Void lattice: a violet crystal grown as a lattice of nodes and struts."""
    s = Sprite()
    s.ellipse(8, 8, 6.4, 6.4, "void", lambda x, y, dx, dy: quant(0.55 - dx * 0.3 - dy * 0.35))
    s.outline("void")
    for y in range(N):
        for x in range(N):
            v = s.get(x, y)
            if v and v[1] > 1 and (x - 2) % 3 == 0 and (y - 2) % 3 == 0:
                s.set(x, y, "void", 5)
            elif v and v[1] > 1 and ((x - 2) % 3 == 0 or (y - 2) % 3 == 0):
                s.set(x, y, "void", min(5, v[1] + 1))
    return s


# ---------------------------------------------------------------------------------------------------------- tools


def handheld(screen_symbol, accent="lumen", antenna=False, keys=True):
    """A handheld device: a graphite case, a lit screen, keys below; an antenna on the top right when wireless."""
    s = Sprite()
    top = 3 if antenna else 1
    s.body(3, top, 12, 14, "graphite")
    s.outline("graphite")
    s.rect(5, top + 2, 10, top + 7, "screen", lambda x, y: 1 if y == top + 2 or x in (5, 10) else 2)
    for x, y, v in screen_symbol(top + 3):
        s.set(x, y, accent, v)
    if keys:
        for x, ramp in ((5, "red"), (7, "lumen"), (8, "lumen"), (10, "green")):
            s.set(x, top + 9, ramp, 4)
            s.set(x, top + 10, ramp, 2)
    if antenna:
        for y in range(0, 3):
            s.set(11, y, "steel", 3)
        s.set(11, 0, "lumen", 4)
    return s


def wave(y0):
    return [(6, y0 + 3, 4), (7, y0 + 2, 4), (8, y0 + 1, 5), (9, y0 + 2, 4), (7, y0 + 3, 3), (6, y0 + 2, 3)]


def bars(y0):
    return [(6, y0 + 3, 3), (7, y0 + 2, 4), (7, y0 + 3, 3), (8, y0 + 1, 5), (8, y0 + 2, 4), (8, y0 + 3, 3),
            (9, y0 + 3, 3), (9, y0 + 2, 4)]


def grid(y0):
    return [(x, y, 4 if y == y0 else 3) for x in (6, 9) for y in (y0, y0 + 2)]


def diagonal(handle, head, length=12, width=1):
    """A tool held at an angle: ``handle`` from the bottom left, ``head`` drawing the top right."""
    s = Sprite()
    for i in range(length):
        x, y = 1 + i, 14 - i
        s.set(x, y, handle, 4 if i % 3 == 0 else 3)
        if width > 1:
            s.set(x + 1, y, handle, 2)
    head(s)
    return s


def shaft(s, x0, x1, ramp, c=15):
    """A shaft along the diagonal x + y = ``c`` from x = ``x0`` to ``x1``, three pixels across so a lit middle line
    survives the outline: the upper left side light, the lower right side dark."""
    for y in range(N):
        for x in range(N):
            d = x + y - c
            if abs(d) <= 1 and x0 <= x - (d > 0) and x - (d < 0) <= x1:
                s.set(x, y, ramp, 4 if d < 0 else 3 if d == 0 else 2)


def storage_wrench():
    """The storage wrench: a steel open-end wrench at an angle, the jaw open to the top right, a lumen grip."""
    s = Sprite()
    shaft(s, 0, 9, "steel")
    shaft(s, 0, 3, "lumen")
    # The head: one prong up, one to the right, the slot between them in the corner.
    s.rect(8, 0, 11, 6, "steel", lambda x, y: 4 if x == 9 else 2 if x == 11 or y == 6 else 3)
    s.rect(8, 3, 15, 6, "steel", lambda x, y: 4 if y == 4 else 2 if y == 6 else 3 if x > 9 else 4)
    s.set(9, 1, "steel", 5)
    return s.outline("steel", "lumen")


def p2p_linker():
    """The P2P linker: a steel rod with a graphite grip and a lumen crystal at its tip."""
    s = Sprite()
    shaft(s, 0, 11, "steel")
    shaft(s, 0, 3, "graphite")
    s.poly([(11.5, 0.5), (15.5, 0.5), (15.5, 4.5), (11.5, 4.5)], "lumen",
           lambda x, y: 5 if (x, y) == (12, 1) else 4 if y == 1 or x == 12 else 2 if y == 4 or x == 15 else 3)
    return s.outline("steel", "graphite", "lumen")


def trace_pen():
    """The trace pen: a graphite pen with a steel nib whose tip glows lumen, and a lumen band."""
    s = Sprite()
    shaft(s, 1, 11, "graphite")
    shaft(s, 5, 6, "lumen")
    shaft(s, 12, 13, "steel")
    s.outline("graphite", "lumen", "steel")
    s.set(14, 1, "lumen", 5)
    s.set(15, 0, "lumen", 3)
    return s


def zone_painter():
    """The zone painter: a paint roller, an orange roller on a steel frame with a graphite grip."""
    s = Sprite()
    shaft(s, 0, 8, "steel", 16)
    shaft(s, 0, 3, "graphite", 16)
    s.rect(7, 1, 15, 6, "orange", lambda x, y: 4 if y == 2 else 2 if y == 5 else 3)
    s.set(8, 2, "orange", 5)
    return s.outline("steel", "graphite", "orange")


def research_scanner():
    s = Sprite()
    s.rect(2, 2, 13, 8, "graphite", lambda x, y: 4 if y == 3 else 2 if y == 7 else 3)
    s.rect(6, 8, 9, 14, "graphite", lambda x, y: 4 if x == 6 else 2 if x == 9 else 3)
    s.outline("graphite")
    s.ellipse(8, 5.5, 2.6, 2.6, "lumen", lambda x, y, dx, dy: 5 if dx < -0.2 and dy < -0.2 else 4 if dy < 0.2 else 3)
    for x in (3, 12):
        s.set(x, 4, "red" if x == 3 else "green", 4)
    s.set(7, 10, "lumen", 4)
    s.set(8, 10, "lumen", 4)
    return s


def network_goggles():
    s = Sprite()
    s.rect(0, 6, 15, 7, "leather", lambda x, y: 4 if y == 6 else 2)
    s.ellipse(4.5, 7.5, 3.6, 3.4, "steel", 3)
    s.ellipse(11.5, 7.5, 3.6, 3.4, "steel", 3)
    s.outline("steel", "leather")
    s.ellipse(4.5, 7.5, 2.2, 2.0, "lumen", lambda x, y, dx, dy: 5 if dx < -0.3 and dy < -0.3 else 4 if dy < 0 else 3)
    s.ellipse(11.5, 7.5, 2.2, 2.0, "lumen", lambda x, y, dx, dy: 5 if dx < -0.3 and dy < -0.3 else 4 if dy < 0 else 3)
    s.set(7, 7, "steel", 2)
    s.set(8, 7, "steel", 2)
    return s


def pocket_uplink():
    s = Sprite()
    s.ellipse(8, 9.5, 5.6, 5.0, "graphite", lambda x, y, dx, dy: quant(0.5 - dx * 0.25 - dy * 0.35))
    s.outline("graphite")
    s.ellipse(8, 9.5, 2.6, 2.4, "lumen", lambda x, y, dx, dy: 5 if dx < -0.3 and dy < -0.3 else 4 if dy < 0 else 3)
    for y in range(1, 5):
        s.set(7, y, "steel", 4)
        s.set(8, y, "steel", 3)
    s.set(6, 1, "steel", 4)
    s.set(9, 1, "steel", 3)
    s.set(7, 0, "lumen", 5)
    s.set(8, 0, "lumen", 4)
    return s


def restock_belt():
    s = Sprite()
    s.rect(0, 6, 15, 9, "leather", lambda x, y: 4 if y == 6 else 2 if y == 9 else 3)
    for x0 in (1, 11):
        s.rect(x0, 8, x0 + 3, 13, "leather", lambda x, y, x0=x0: 4 if y == 8 else 2 if y == 13 else 3)
        s.set(x0 + 1, 10, "steel", 4)
        s.set(x0 + 2, 10, "steel", 4)
    s.outline("leather")
    s.rect(6, 5, 9, 10, "steel", lambda x, y: 4 if y == 5 else 2 if y == 10 else 3)
    s.rect(7, 7, 8, 8, "lumen", 4)
    s.outline("steel", "leather")
    s.rect(7, 7, 8, 8, "lumen", 4)
    return s


def cable_layer():
    """The cable layer: a reel of cable on a graphite gun body with a nozzle on the right."""
    s = Sprite()
    s.rect(4, 7, 14, 10, "graphite", lambda x, y: 4 if y == 7 else 2 if y == 10 else 3)
    s.rect(5, 10, 7, 14, "graphite", lambda x, y: 4 if x == 5 else 3)
    s.rect(13, 8, 15, 9, "steel", 3)
    s.outline("graphite", "steel")
    s.ellipse(5, 5, 4.2, 4.2, "steel", lambda x, y, dx, dy: quant(0.5 - dx * 0.3 - dy * 0.3))
    s.outline("steel")
    s.ellipse(5, 5, 2.6, 2.6, "lumen", lambda x, y, dx, dy: 4 if (x + y) % 2 else 3)
    s.set(5, 5, "steel", 1)
    return s


def tablet(symbol, accent="lumen"):
    """A tablet: a steel frame round a large screen."""
    s = Sprite()
    s.body(2, 1, 13, 14, "steel")
    s.outline("steel")
    s.rect(4, 3, 11, 11, "screen", lambda x, y: 1 if y == 3 or x in (4, 11) else 2)
    for x, y, v in symbol:
        s.set(x, y, accent, v)
    s.set(7, 13, "steel", 1)
    s.set(8, 13, "steel", 1)
    return s


def logic_remote():
    s = Sprite()
    s.body(4, 1, 11, 14, "graphite")
    s.outline("graphite")
    s.rect(5, 3, 10, 6, "screen", lambda x, y: 1 if y == 3 else 2)
    s.set(6, 4, "signal", 4)
    s.set(7, 5, "signal", 4)
    s.set(8, 4, "signal", 4)
    s.set(9, 5, "signal", 4)
    for x, y, ramp in ((6, 8, "red"), (9, 8, "green"), (6, 11, "lumen"), (9, 11, "lumen"), (7, 10, "amber"),
                       (8, 10, "amber")):
        s.set(x, y, ramp, 4)
        s.set(x, y + 1, ramp, 2)
    return s


def signal_probe():
    s = Sprite()
    s.body(4, 1, 11, 10, "graphite")
    s.outline("graphite")
    s.rect(5, 3, 10, 6, "screen", lambda x, y: 1 if y == 3 else 2)
    for x, y, v in wave(3):
        s.set(x, y, "signal", v)
    for y in range(10, 15):
        s.set(7, y, "steel", 4)
        s.set(8, y, "steel", 2)
    s.set(7, 15, "steel", 1)
    s.set(8, 15, "steel", 1)
    s.set(6, 8, "red", 4)
    s.set(9, 8, "green", 4)
    return s


def book(cover, emblem):
    """A book seen from the front: a cover in ``cover``'s colours, the spine on the left, pages on the right edge."""
    s = Sprite()
    s.rect(3, 1, 13, 14, cover, lambda x, y: 2 if x <= 4 else 4 if y == 2 else 3)
    s.rect(13, 2, 13, 13, "paper", lambda x, y: 4 if y % 2 else 3)
    s.outline(cover, "paper")
    s.rect(13, 2, 13, 13, "paper", lambda x, y: 4 if y % 2 else 3)
    s.set(5, 2, cover, 5)
    emblem(s)
    return s


def paper(lines, extra=None, ramp="paper"):
    """A sheet: a page with a folded top right corner and lines of writing."""
    s = Sprite()
    s.rect(3, 1, 12, 14, ramp, lambda x, y: 4 if y < 3 else 3 if x < 11 else 2)
    s.clear(12, 1)
    s.clear(11, 1)
    s.clear(12, 2)
    s.set(11, 2, ramp, 2)
    s.outline(ramp)
    s.set(10, 1, ramp, 1)
    s.set(11, 2, ramp, 1)
    s.set(12, 3, ramp, 1)
    s.set(10, 2, ramp, 4)
    s.set(10, 3, ramp, 2)
    s.set(11, 3, ramp, 2)
    for y, x0, x1 in lines:
        for x in range(x0, x1 + 1):
            s.set(x, y, ramp, 2 if ramp == "paper" else 4)
    if extra:
        extra(s)
    return s


def pattern(encoded):
    """A pattern slate: a thin graphite slab in a steel frame, blank or with a lit encoded grid."""
    s = Sprite()
    s.body(2, 2, 13, 13, "steel")
    s.outline("steel")
    s.rect(4, 4, 11, 11, "screen", lambda x, y: 1 if y == 4 or x in (4, 11) else 2)
    if encoded:
        for x in (5, 7, 8, 10):
            for y in (6, 8, 10):
                if (x + y) % 3:
                    s.set(x, y, "lumen", 4 if y == 6 else 3)
        s.set(5, 6, "lumen", 5)
    else:
        for x in range(5, 11):
            s.set(x, 7, "screen", 3)
            s.set(x, 9, "screen", 3)
    return s


def quarantine_tag():
    s = Sprite()
    s.poly([(5, 4), (11, 4), (13, 6), (13, 15), (3, 15), (3, 6)], "orange",
           lambda x, y: 4 if y < 7 else 2 if y > 13 else 3)
    s.mirror()  # the polygon test breaks ties on the slanted corners differently on each side
    s.outline("orange")
    s.set(7, 6, "orange", 1)
    s.set(8, 6, "orange", 1)
    for x in range(5, 11):
        s.set(x, 9, "graphite", 2)
        s.set(x, 11, "graphite", 2)
    for x in (5, 7, 8, 10):
        s.set(x, 13, "graphite", 2)
    for y in range(1, 6):
        s.set(7, y, "paper", 4)
        s.set(8, y, "paper", 3)
    s.set(6, 0, "paper", 3)
    s.set(9, 0, "paper", 3)
    return s


def skip_token():
    s = Sprite()
    s.ellipse(8, 8, 6.4, 6.4, "gold", lambda x, y, dx, dy: quant(0.55 - dx * 0.3 - dy * 0.35))
    s.outline("gold")
    s.ellipse(8, 8, 4.4, 4.4, "gold", lambda x, y, dx, dy: quant(0.5 + dx * 0.25 + dy * 0.3))
    s.poly([(8, 4.5), (11.5, 8), (8, 11.5), (4.5, 8)], "lumen", lambda x, y: 4 if x + y < 16 else 3)
    s.set(7, 6, "lumen", 5)
    return s


def puzzle_cartridge():
    s = Sprite()
    s.body(2, 1, 13, 14, "steel")
    s.outline("steel")
    s.rect(4, 3, 11, 9, "signal", lambda x, y: 4 if y == 3 else 2 if y == 9 else 3)
    for x, y in ((5, 5), (6, 5), (6, 6), (9, 6), (9, 7), (10, 7)):
        s.set(x, y, "paper", 5)
    for x in PINS:
        s.set(x, 13, "gold", 3)
    s.set(4, 11, "steel", 1)
    s.set(5, 11, "steel", 1)
    return s


def logic_card():
    s = card("steel", "logic", "red")
    return s


SYMBOLS["logic"] = _pts(["##...", "#.#.#", "#..##", "#.#.#", "##..."], 6, 5)


def outputs():
    o = {}
    for fam in FAMILIES:
        for tier in ("basic", "advanced", "elite"):
            o[f"{tier}_{fam}_processor"] = processor(fam, tier)
        o[f"{fam}_die"] = die(fam, "basic")
        o[f"advanced_{fam}_die"] = die(fam, "advanced")
        o[f"elite_{fam}_die"] = die(fam, "elite")
        o[f"{fam}_mask"] = mask(fam)
    o["silicon_wafer"] = wafer("silicon")
    o["doped_wafer"] = wafer("silicon", specks="data")
    o["advanced_doped_wafer"] = wafer("silicon", specks="data", grid="gold")
    o["data_crystal_wafer"] = wafer("data")
    o["ember_doped_wafer"] = wafer("silicon", specks="ember")
    o["silicon"] = silicon()
    o["silicon_blend"] = blend("silicon", "lumen")
    o["signal_alloy_blend"] = blend("signal", "copper")
    o["display_glass_blend"] = blend("glass", "lumen")
    o["copper_wire"] = spool("copper")
    o["insulated_copper_wire"] = spool("lumen")
    o["superconductor_wire"] = spool("niobium", "tantalum")
    o["glass_fibre"] = spool("glass")
    o["tantalum_capacitor"] = capacitor()
    o["circuit_board"] = circuit_board()
    o["casing_plate"] = plate("steel")
    o["display_glass"] = plate("glass", glass=True)
    o["oscillator"] = oscillator()
    for size in CELL_COLOURS:
        o[f"{size}_cell_casing"] = cell(size, False)
        o[f"{size}_storage_cell"] = cell(size, True)
    for n in range(1, 7):
        o[f"memory_module_{n}"] = memory_module(n)
    o["item_controller_chip"] = controller_chip("item")
    o["fluid_controller_chip"] = controller_chip("fluid")
    o["partition_addon"] = card("tantalum", "partition", "lumen")
    o["read_only_addon"] = card("tantalum", "read_only", "lumen")
    o["void_overflow_addon"] = card("tantalum", "void", "void")
    o["speed_card"] = card("steel", "speed", "lumen")
    o["crafting_card"] = card("steel", "crafting", "lumen")
    o["blueprint_card"] = card("steel", "blueprint", "data")
    o["logic_card"] = logic_card()
    o["batch_amplifier_card"] = card("steel", "batch", "lumen")
    o["efficiency_core_card"] = card("steel", "bolt", "green")
    o["surge_regulator_card"] = card("steel", "reserve", "amber")
    o["view_filter"] = card("niobium", "funnel", "lumen")
    o["precision_module"] = module("ember", "prec")
    o["speed_module"] = module("ember", "spd")
    o["efficiency_module"] = module("ember", "eff")
    o["void_module"] = module("void", "vd")
    o["void_lattice"] = void_lattice()
    o["storage_wrench"] = storage_wrench()
    o["p2p_linker"] = p2p_linker()
    o["trace_pen"] = trace_pen()
    o["zone_painter"] = zone_painter()
    o["research_scanner"] = research_scanner()
    o["network_analyzer"] = handheld(bars, antenna=True)
    o["wireless_terminal"] = handheld(grid, antenna=True)
    o["network_goggles"] = network_goggles()
    o["pocket_uplink"] = pocket_uplink()
    o["restock_belt"] = restock_belt()
    o["cable_layer"] = cable_layer()
    o["crafting_tablet"] = tablet([(x, y, 4 if (x + y) % 2 else 3) for x in (5, 6, 7, 8, 9, 10)
                                   for y in (4, 5, 6, 7, 8, 9, 10) if (x - 5) % 2 == 0 and (y - 4) % 2 == 0
                                   and y < 10 and x < 10] + [(9, 6, 5), (10, 7, 4), (9, 8, 5)])
    o["logic_remote"] = logic_remote()
    o["signal_probe"] = signal_probe()
    o["storage_manual"] = book("data", lambda s: [s.set(x, y, "lumen", v) for x, y, v in
                                                  ((8, 5, 4), (9, 6, 4), (8, 7, 3), (7, 6, 3), (8, 6, 5))])
    o["usage_report_book"] = book("leather", lambda s: [s.set(x, y, "lumen", v) for x, y, v in
                                                        ((7, 9, 3), (8, 8, 4), (8, 9, 3), (9, 6, 4), (9, 7, 4),
                                                         (9, 8, 4), (9, 9, 3), (10, 7, 4), (10, 8, 4), (10, 9, 3))])
    o["research_notes"] = paper([(4, 4, 9), (6, 4, 10), (8, 4, 7)],
                                lambda s: [s.set(x, y, "lumen", 4) for x, y in ((5, 11), (6, 10), (7, 11), (8, 10),
                                                                                (9, 11), (10, 10))])
    o["setup_blueprint"] = paper([(4, 4, 9), (6, 4, 10), (9, 4, 6), (9, 9, 10), (11, 4, 10)], ramp="blueprint")
    o["blank_pattern"] = pattern(False)
    o["encoded_pattern"] = pattern(True)
    o["quarantine_tag"] = quarantine_tag()
    o["research_skip_token"] = skip_token()
    o["puzzle_cartridge"] = puzzle_cartridge()
    return o


def write(out, o):
    out.mkdir(parents=True, exist_ok=True)
    for name, sprite in o.items():
        sprite.image().save(out / (name + ".png"))


def sheet(o, path, sc=6, cols=10):
    from PIL import Image, ImageDraw

    names = sorted(o)
    cw, ch = N * sc + 6, N * sc + 16
    rows = (len(names) + cols - 1) // cols
    im = Image.new("RGBA", (cols * cw, rows * ch), (139, 139, 139, 255))
    d = ImageDraw.Draw(im)
    for i, n in enumerate(names):
        t = o[n].image().resize((N * sc, N * sc), Image.NEAREST)
        x, y = (i % cols) * cw + 3, (i // cols) * ch + 2
        im.alpha_composite(t, (x, y))
        d.text((x, y + N * sc + 1), n[:17], fill=(0, 0, 0, 255))
    im.save(path)


# Icons whose design is symmetric left to right. Left out on purpose: tools held at an angle, books (spine on the
# left), sheets with a folded corner, handhelds with the antenna on the right, organic shapes (blends, silicon), speckled
# wafers, display glass (diagonal sheen) and directional or data symbols (speed card chevrons, the bolt, the logic
# gate, the blueprint plan, the encoded pattern, screen content).
SYMMETRIC = (
    [f"{t}_{f}_processor" for f in FAMILIES for t in ("basic", "advanced", "elite")]
    + [f"{p}{f}_die" for f in FAMILIES for p in ("", "advanced_", "elite_")]
    + [f"{f}_mask" for f in FAMILIES]
    + [f"{size}_{kind}" for size in CELL_COLOURS for kind in ("cell_casing", "storage_cell")]
    + [f"memory_module_{n}" for n in range(1, 7)]
    + ["precision_module", "speed_module", "efficiency_module", "void_module", "item_controller_chip",
       "fluid_controller_chip", "partition_addon", "read_only_addon", "void_overflow_addon", "crafting_card",
       "batch_amplifier_card", "surge_regulator_card", "copper_wire", "insulated_copper_wire", "superconductor_wire",
       "glass_fibre", "tantalum_capacitor", "circuit_board", "casing_plate", "oscillator", "silicon_wafer",
       "data_crystal_wafer", "blank_pattern", "void_lattice", "research_skip_token", "network_goggles", "restock_belt",
       "pocket_uplink", "quarantine_tag", "view_filter"]
)


def asymmetric(sprite):
    """Pixels (x, y) of the left half whose mirror image differs in silhouette, ramp or darkest shade."""
    bad = []
    for y in range(N):
        for x in range(N // 2):
            a, b = sprite.get(x, y), sprite.get(N - 1 - x, y)
            if (a is None) != (b is None):
                bad.append((x, y))
            elif a is not None and (a[0] != b[0] or (a[1] == 1) != (b[1] == 1)):
                bad.append((x, y))
    return bad


def check_symmetry(o):
    failed = 0
    for name in SYMMETRIC:
        bad = asymmetric(o[name])
        if bad:
            failed += 1
            print(f"{name} is not mirror-symmetric at {bad[:6]}")
    if failed:
        return 1
    print(len(SYMMETRIC), "symmetric item icons mirror")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--check-symmetry", action="store_true", help="check the SYMMETRIC icons, write nothing")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--sheet", help="a contact sheet PNG to write")
    ap.add_argument("--only", help="comma-separated name parts to put on the sheet")
    args = ap.parse_args()
    items = outputs()
    if args.check_symmetry:
        sys.exit(check_symmetry(items))
    if args.sheet:
        shown = items if not args.only else {k: v for k, v in items.items() if any(p in k for p in
                                                                                     args.only.split(","))}
        sheet(shown, args.sheet)
    else:
        write(Path(args.out), items)
    print(len(items), "item textures")
