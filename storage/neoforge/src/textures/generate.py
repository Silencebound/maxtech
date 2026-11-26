#!/usr/bin/env python3
"""Draws Storage's block textures in the "light grey" style (owner decision 2026-10-07).

Items are drawn by generate_items.py.

Light grey casing with a clean bevel (light top and left edge, darker bottom and right edge, a thin inner frame), one
clear feature per block in a recess (darker floor, shaded rim), cyan accents. Directional devices share a back
(``port/stub_end``, the cable connector) and a side with an arrow pointing at the front (``facing_side``); their models
turn the side so the arrow points at the front from every face. Everything is 16 x 16 and written over the existing
PNGs. Animated textures are vertical strips whose .mcmeta files stay as they are.

Shading is applied when an image is written (``Img.finished``), after any mirroring, so a symmetric drawing still gets
its light from the top left: the casing's bevel and frame land only on untouched casing pixels, and every recess drawn
with ``socket`` gets its rim shaded where nothing was drawn over it.

Usage: python generate.py [--out DIR] [--sheet DIR]
Needs Pillow. Output defaults to the Storage resources next to this script's repository root.
"""
import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw

N = 16
ROOT = Path(__file__).resolve().parents[3]
DEFAULT_OUT = ROOT / "common/src/main/resources/assets/maxtech_storage/textures"


def hex_(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def mix(c, f):
    return tuple(max(0, min(255, round(v + (255 - v) * f if f >= 0 else v * (1 + f)))) for v in c)


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def rng(seed):
    state = [seed & 0xFFFFFFFF]

    def nxt():
        state[0] = (state[0] + 0x6D2B79F5) & 0xFFFFFFFF
        a = state[0]
        t = ((a ^ (a >> 15)) * (1 | a)) & 0xFFFFFFFF
        t = ((t + (((t ^ (t >> 7)) * (61 | t)) & 0xFFFFFFFF)) & 0xFFFFFFFF) ^ t
        return (((t ^ (t >> 14)) & 0xFFFFFFFF)) / 4294967296

    return nxt


# The "light" palette of the owner's sketch (designs/simple_textures.py), plus material and status colours.
P = dict(base=(156, 160, 166), hi=(196, 199, 204), lo=(112, 116, 123), edge=(74, 78, 85), inset=(52, 56, 63),
         inset_hi=(78, 83, 91), acc=(84, 214, 230), glow=(190, 246, 252), acc_lo=(40, 140, 160), warn=(240, 170, 60),
         ok=(110, 220, 110), red=(222, 84, 72), screen=(28, 40, 52), q=hex_("#7fe3f2"), pcb=hex_("#2f6a4e"),
         gold=hex_("#d8b04e"), wire=(232, 150, 60))
# Older names the item drawings use: the dark parts are the recess floor, the light parts the casing's highlight.
P["b2"] = P["base"]
P["dark"] = P["inset"]
P["light"] = P["hi"]
PEAK = P["glow"]  # the brightest accent; the animated peaks reach it
RIM = (1, 2, 3)  # placeholder for a recess rim, shaded in Img.finished


class Img:
    def __init__(self, w=N, h=N):
        self.w, self.h = w, h
        self.px = [None] * (w * h)
        self.casing = False
        self.recesses = []

    def set(self, x, y, c, a=255, raw=False):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = (c[0], c[1], c[2], a)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y * self.w + x]
        return None

    def rect(self, x0, y0, x1, y1, c, a=255, raw=False):
        """Fills x0..x1 and y0..y1 inclusive."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c, a, raw)

    def sym(self, both=False):
        for y in range(self.h):
            for x in range(self.w // 2):
                self.px[y * self.w + (self.w - 1 - x)] = self.px[y * self.w + x]
        for r in list(self.recesses):
            m = (self.w - 1 - r[2], r[1], self.w - 1 - r[0], r[3])
            if m not in self.recesses:
                self.recesses.append(m)
        if both:
            for y in range(self.h // 2):
                for x in range(self.w):
                    self.px[(self.h - 1 - y) * self.w + x] = self.px[y * self.w + x]
            for r in list(self.recesses):
                m = (r[0], self.h - 1 - r[3], r[2], self.h - 1 - r[1])
                if m not in self.recesses:
                    self.recesses.append(m)
        return self

    def outline(self, c=None):
        c = c or mix(P["inset"], -0.3)
        out = []
        for y in range(self.h):
            for x in range(self.w):
                if self.get(x, y) is None:
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        n = self.get(x + dx, y + dy)
                        if n is not None and n[3] > 0:
                            out.append((x, y))
                            break
        for x, y in out:
            self.set(x, y, c)
        return self

    def finished(self):
        """The pixels with the light applied: the casing's bevel and inner frame on untouched casing, then the rims."""
        px = list(self.px)
        w = self.w

        def put(x, y, c, only_base=True):
            p = px[y * w + x]
            if not only_base or (p is not None and p[:3] == P["base"]):
                px[y * w + x] = c + (255,)

        if self.casing:
            for i in range(N):
                put(i, 0, P["hi"])
                put(0, i, P["hi"])
            for i in range(N):
                put(i, 15, P["lo"])
                put(15, i, P["lo"])
            for i in range(2, 14):
                put(i, 2, P["lo"])
                put(2, i, P["lo"])
            for i in range(2, 14):
                put(i, 13, P["hi"])
                put(13, i, P["hi"])
        for x0, y0, x1, y1 in self.recesses:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    p = px[y * w + x]
                    if p is not None and p[:3] == RIM:
                        px[y * w + x] = (P["inset_hi"] if x == x1 or y == y1 else P["edge"]) + (255,)
        return [p if p is None or p[:3] != RIM else P["edge"] + (255,) for p in px]

    def image(self):
        im = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        im.putdata([p if p else (0, 0, 0, 0) for p in self.finished()])
        return im


def panel(seed=0, noise=0.0):
    """The casing: flat light grey; the bevel and the inner frame are added when the image is finished."""
    a = Img()
    a.rect(0, 0, N - 1, N - 1, P["base"])
    a.casing = True
    return a


def seam(a):
    """The old style's dark edge; the casing's bevel replaces it."""
    return a


def socket(a, x0, y0, x1, y1, floor=None):
    """A recess whose floor is x0..x1, y0..y1, with a one pixel rim around it shaded from the top left."""
    a.rect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, RIM)
    a.rect(x0, y0, x1, y1, floor or P["inset"])
    a.recesses.append((x0 - 1, y0 - 1, x1 + 1, y1 + 1))


def dim():
    return P["acc_lo"]


def facing_side():
    """Side of a directional device: casing with an arrow pointing to the texture's top, which the models turn to the
    device's front on every side face."""
    a = panel()
    for i, y in enumerate((4, 5, 6)):
        a.rect(7 - i, y, 8 + i, y, P["acc"])
    a.rect(7, 4, 8, 4, P["glow"])
    a.rect(7, 7, 8, 11, P["acc_lo"])
    return a


def connector():
    """Back of a directional device, where it links to the network: a recessed cable socket with a lit link point."""
    a = panel()
    socket(a, 5, 5, 10, 10)
    a.rect(6, 6, 9, 9, P["inset_hi"])
    a.rect(7, 7, 8, 8, P["acc_lo"])
    a.set(7, 7, P["acc"])
    return a


# ---------------------------------------------------------------- block textures
def pulse(t, n, phase=0.0, cycles=1):
    return 0.5 + 0.5 * math.sin(2 * math.pi * (cycles * t / n - phase))


def casing_top():
    a = panel()
    socket(a, 5, 5, 10, 10, P["inset_hi"])
    a.rect(7, 7, 8, 8, P["edge"])
    return a


def window_front():
    a = panel()
    socket(a, 4, 5, 11, 10)
    return a


def small_leds(a, y=13):
    for x in (5, 7, 8, 10):
        a.set(x, y, dim())


def assembler_front():
    a = window_front()
    a.rect(7, 7, 8, 8, P["inset_hi"])
    small_leds(a)
    return a


def assembler_active(t):
    a = Img()
    k = t % 4
    b = pulse(t, 8, 0, 1)
    for x in (4 + k, 11 - k):
        a.set(x, 6, lerp(dim(), PEAK, 0.55))
        a.set(x, 9, lerp(dim(), PEAK, 0.55))
    for x in (7, 8):
        for y in (7, 8):
            a.set(x, y, lerp(P["acc"], PEAK, 0.2 + 0.8 * b))
    for x in (5, 7, 8, 10):
        a.set(x, 13, lerp(dim(), P["acc"], b))
    return a.sym(False)


def fabricator_front():
    a = panel()
    socket(a, 3, 6, 12, 9)
    a.rect(3, 6, 12, 7, P["lo"])
    a.rect(3, 6, 12, 6, P["hi"])
    small_leds(a, 12)
    return a


def fabricator_active(t):
    a = Img()
    tri = [0, 1, 2, 2, 1, 0, 0, 0][t % 8]
    for x in range(3, 13):
        a.set(x, 6, P["inset"], 255)
    a.rect(3, 6 + tri, 12, 7 + tri, P["lo"])
    a.rect(3, 6 + tri, 12, 6 + tri, P["hi"])
    a.rect(3, 9, 12, 9, lerp(P["acc"], PEAK, 0.15 + 0.75 * (tri / 2)))
    for x in (5, 7, 8, 10):
        a.set(x, 12, lerp(dim(), P["acc"], tri / 2))
    return a.sym(False)


def lithograph_front():
    a = window_front()
    for x, y in [(7, 6), (8, 6), (6, 7), (9, 7), (6, 8), (9, 8), (7, 9), (8, 9)]:
        a.set(x, y, P["inset_hi"])
    small_leds(a)
    return a


def lithograph_active(t):
    a = Img()
    y = 5 + (t % 6)
    for x in range(5, 11):
        a.set(x, y, lerp(P["acc"], PEAK, 0.6))
        if y > 5:
            a.set(x, y - 1, P["acc"], 140)
    return a


def fuel_front():
    a = panel()
    socket(a, 4, 5, 11, 10)
    for x in (5, 7, 8, 10):
        a.rect(x, 5, x, 10, P["inset_hi"])
    return a


FIRE = [hex_("#9a3412"), hex_("#d0561c"), hex_("#ec8a24"), hex_("#ffc248")]


def fuel_active(t):
    a = Img()
    r = rng(900 + t)
    for x in range(4, 8):
        height = 2 + int(r() * 4)
        for h in range(height):
            y = 10 - h
            a.set(x, y, FIRE[min(3, int(h / height * 4))], 255)
    return a.sym(False)


def fuel_top():
    a = panel()
    for y in (4, 7, 10):
        socket(a, 4, y, 11, y)
    return a


def lens():
    a = panel()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 6.2:
                a.set(x, y, P["lo"] if d > 5.0 else (P["inset"] if d > 3.0 else P["inset_hi"]))
    a.rect(7, 7, 8, 8, P["acc"])
    a.set(7, 7, P["glow"])
    return a


def workbench_front():
    a = panel()
    socket(a, 4, 4, 11, 9)
    a.rect(7, 6, 8, 7, P["inset_hi"])
    for x in (5, 7, 8, 10):
        a.set(x, 12, dim())
    return a


def workbench_top():
    a = panel()
    socket(a, 4, 4, 11, 11)
    a.rect(6, 6, 9, 9, P["inset_hi"])
    a.rect(7, 7, 8, 8, P["acc"])
    a.set(7, 7, P["glow"])
    return a


def chest_front():
    a = panel()
    socket(a, 3, 3, 12, 12, P["lo"])
    a.rect(7, 3, 8, 12, P["edge"])
    a.rect(7, 7, 8, 8, P["acc"])
    return a


def chest_top():
    a = panel()
    socket(a, 4, 4, 11, 11, P["lo"])
    return a


def creative_source():
    """Casing with a recessed bright cyan core and four magenta corner studs, to read as a creative block."""
    a = panel()
    stud = hex_("#d05cc0")
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        a.set(x, y, stud)
    socket(a, 5, 5, 10, 10)
    a.rect(6, 6, 9, 9, P["acc"])
    a.rect(7, 7, 8, 8, P["glow"])
    return a


def condition_front(on):
    """Casing with a recessed lamp window and two comparator-like studs; the lamp lights while the output is on."""
    a = panel()
    socket(a, 5, 5, 10, 10)
    a.rect(6, 6, 9, 9, P["acc"] if on else P["inset_hi"])
    if on:
        a.rect(7, 7, 8, 8, P["glow"])
    for x in (3, 12):
        a.rect(x, 3, x, 4, P["red"] if on else P["edge"])
        a.rect(x, 11, x, 12, P["lo"])
    return a


def energy_cell_side(charge):
    """Casing with a recessed gauge of four cells, lit bottom up by the cell's charge (0 to 4); full glows brighter."""
    a = panel()
    socket(a, 5, 2, 10, 13)
    # four cells of two rows each, a row apart, bottom first
    for seg, (ya, yb) in enumerate(((11, 12), (8, 9), (5, 6), (2, 3))):
        if seg < charge:
            c = P["glow"] if charge == 4 else P["acc"]
        else:
            c = P["inset_hi"]
        a.rect(6, ya, 9, yb, c)
    return a


def energy_cell_end(tier):
    """Top and bottom: a plate with one, two or three cyan studs for the tier."""
    a = panel()
    socket(a, 3, 6, 12, 9)
    xs = {1: (7,), 2: (5, 9), 3: (4, 7, 10)}[tier]
    for x in xs:
        a.rect(x, 7, x + 1, 8, P["acc"])
        a.set(x, 7, P["glow"])
    return a


def drive_front():
    """Eight slot bays (the cells' textures are drawn over them by the drive model)."""
    a = panel()
    for col in (1, 9):
        for row in (3, 6, 9, 12):
            a.rect(col, row, col + 5, row + 1, P["inset"])
            a.rect(col, row, col + 5, row, P["edge"])
            a.rect(col, row + 2, col + 5, row + 2, P["hi"]) if row < 12 else None
    return a


DRIVE_LED = {"low": P["ok"], "high": P["warn"], "full": P["red"]}


def drive_cell(band_color):
    """6 x 2 window of the cell's front (the model samples the top left corner): a light handle and an LED pair."""
    a = Img()
    for y in range(N):
        for x in range(N):
            a.set(x, y, P["lo"])
    a.rect(0, 0, 5, 0, P["hi"])
    a.rect(0, 1, 5, 1, P["base"])
    a.rect(0, 0, 0, 1, P["edge"])
    a.rect(5, 0, 5, 1, P["edge"])
    a.rect(1, 1, 2, 1, P["lo"])
    a.rect(3, 0, 4, 1, band_color if band_color else P["inset_hi"])
    return a


def drive_cell_side():
    a = Img()
    a.rect(0, 0, N - 1, N - 1, P["base"])
    return a


LUMEN = [
    "................", "................", "..#..........#..", ".###........###.", ".###...##...###.", "..#...####...#..",
    "......####......", "...#...##...#...", "..###......###..", "..###......###..", "...#...##...#...",
    "......####......", ".#....####....#.", "###....##....###", ".#............#.", "................"]


def shaded_overlay(mask, base, lite, dark, gold=None):
    a = Img()
    on = lambda x, y: 0 <= x < N and 0 <= y < N and mask[y][x] == "#"
    for y in range(N):
        for x in range(N):
            if not on(x, y):
                continue
            c = base
            if not on(x - 1, y) and not on(x, y - 1):
                c = lite
            elif not on(x + 1, y) or not on(x, y + 1):
                c = dark
            a.set(x, y, c)
    return a


def lumen_overlay():
    return shaded_overlay(LUMEN, P["q"], mix(P["q"], 0.4), mix(P["q"], -0.35))


def tantalite_overlay():
    base = hex_("#7a6c60")
    return shaded_overlay(LUMEN, base, hex_("#a0a6ba"), mix(base, -0.4))


# ---------------------------------------------------------------- cables, terminals, ports
# Storage cables, look "J" (owner decision 2026-10-15): a clear glass sleeve 4 px thick with one lane inside it. The
# model draws every cable texture one texel per block pixel: along an arm the real coordinates, across it the arm's own
# pixels, so the 4 px sleeve shows rows 6 to 9 of its sprite and the 2 px lane rows 7 and 8. Core faces and closed
# ends show one 4 x 4 cell of the core sprites, the cell picked by which of the face's edges an arm leaves through
# (CableGeometry.cell: bit 1 lowest u, 2 highest u, 4 lowest v, 8 highest v), so the sleeve's edges run round
# corners and junctions. The lane and the covered sleeve are grey masks the model tints with the cable's colour.
SLEEVE_EDGE = hex_("#d6e6ea")  # the mockup's lit edge; Minecraft's face shading gives the darker sides
SLEEVE_EDGE_ALPHA = 210
SLEEVE_CLEAR_ALPHA = 48
# The covered sleeve is drawn below full white so its lit tier pips can read lighter than the dye colour.
COVERED_EDGE = 160
COVERED_FILL = 190
PIP_LIT = 255
PIP_UNLIT = 115
# Tier pips in a cell's 2 x 2 middle, in lighting order: top left, top right, bottom left, bottom right.
PIPS = ((1, 1), (2, 1), (1, 2), (2, 2))


def cable_stripe(edge, fill, edge_a=255, fill_a=255, outside_a=255):
    """A sprite whose rows 6 and 9 are the edge and rows 7 and 8 the fill, constant along u."""
    a = Img()
    for y in range(N):
        c, alpha = (edge, edge_a) if y in (6, 9) else (fill, fill_a if y in (7, 8) else outside_a)
        for x in range(N):
            if alpha:
                a.set(x, y, c, alpha)
    return a


def cable_cells(edge, fill, edge_a=255, fill_a=255):
    """16 cells of 4 x 4, cell n closed (edge) on every side whose bit is clear in n; corners are always edge."""
    a = Img()
    for n in range(16):
        cx, cy = n % 4 * 4, n // 4 * 4
        for j in range(4):
            for i in range(4):
                closed = ((i == 0 and not n & 1) or (i == 3 and not n & 2) or (j == 0 and not n & 4)
                          or (j == 3 and not n & 8) or (i in (0, 3) and j in (0, 3)))
                a.set(cx + i, cy + j, edge if closed else fill, edge_a if closed else fill_a)
    return a


def cable_sleeve():
    """The glass sleeve along an arm: light edges, nearly clear between them, nothing outside rows 6 to 9."""
    return cable_stripe(SLEEVE_EDGE, SLEEVE_EDGE, SLEEVE_EDGE_ALPHA, SLEEVE_CLEAR_ALPHA, 0)


def cable_sleeve_core():
    return cable_cells(SLEEVE_EDGE, SLEEVE_EDGE, SLEEVE_EDGE_ALPHA, SLEEVE_CLEAR_ALPHA)


def cable_covered():
    """The covered cable's opaque sleeve: a light grey mask with slightly darker edges, tinted with the colour."""
    g = lambda v: (v, v, v)
    return cable_stripe(g(COVERED_EDGE), g(COVERED_FILL))


def cable_covered_core(tier=0):
    """The covered sleeve's cells; with a tier (look "K1", owner decision 2026-10-15) each cell's 2 x 2 middle holds
    four pips, the first `tier` of them lit (lighter than the sleeve) and the rest unlit (darker). Closed arm ends use
    the cells without pips."""
    g = lambda v: (v, v, v)
    a = cable_cells(g(COVERED_EDGE), g(COVERED_FILL))
    if tier:
        for n in range(16):
            cx, cy = n % 4 * 4, n // 4 * 4
            for k, (i, j) in enumerate(PIPS):
                a.set(cx + i, cy + j, g(PIP_LIT if k < tier else PIP_UNLIT))
    return a


def cable_lane(fiber):
    """The lane mask, opaque so it stays in the solid layer; a fiber lane is dimmer with a 2 px dot every 8 px,
    centred on the block faces and the block's middle so it is the same on every face whichever way u runs."""
    a = Img()
    for x in range(N):
        v = 255 if not fiber or x in (15, 0, 7, 8) else 200
        for y in range(N):
            a.set(x, y, (v, v, v))
    return a


def cable_lane_core(tier):
    """The lane mask for a glass cable's lane core faces: the core face is the 2 x 2 middle (texels 7 and 8 both
    ways, its default UVs) and holds four pips in PIPS' order, the first `tier` of them lit (the lane's full colour)
    and the rest unlit (darker), so a glass, fiber or smart cable shows its tier by shape too (owner decision
    2026-10-15). Opaque, tinted and lit with the lane like the rest of it."""
    a = cable_lane(False)
    for k, (i, j) in enumerate(PIPS):
        v = PIP_LIT if k < tier else PIP_UNLIT
        a.set(6 + i, 6 + j, (v, v, v))
    return a


# Tier marks on the port's rim, left and top halves (the symmetry mirrors them): tier 2 two pips per side, tier 3 three
# (the middle one two pixels wide). Tier 1 has none.
PORT_MARKS = {1: (), 2: (6,), 3: (4, 7)}


def port_front(export, tier=1):
    """The port's working face: a small ring in the mode's colour (import cyan, export amber) with arrows on its four
    sides, pointing in for an intake and out for an outlet; an intake lights its centre, an outlet its ring."""
    c = P["warn"] if export else P["acc"]
    bright = mix(c, 0.55)
    a = panel()
    socket(a, 3, 3, 12, 12)
    for t in range(6, 10):
        for (x, y) in ((t, 6), (t, 9), (6, t), (9, t)):
            a.set(x, y, c if export else mix(c, -0.45))
    a.rect(7, 7, 8, 8, P["inset_hi"] if export else bright)
    # arrows between the recess's edge and the ring: the top and left ones drawn, the symmetry makes the others
    wide, narrow = (4, 3) if export else (3, 4)
    a.rect(6, wide, 9, wide, c)
    a.rect(7, narrow, 8, narrow, bright if export else c)
    a.rect(wide, 6, wide, 9, c)
    a.rect(narrow, 7, narrow, 8, bright if export else c)
    for m in PORT_MARKS[tier]:
        a.set(m, 1, P["acc_lo"])
        a.set(1, m, P["acc_lo"])
    return a.sym(True)


def interface_face():
    """The interface: a recess with a 3 x 3 grid of stock cells, the centre lit."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    for gy in range(3):
        for gx in range(3):
            x, y = 4 + gx * 3, 4 + gy * 3
            a.rect(x, y, x + 1, y + 1, P["acc"] if (gx, gy) == (1, 1) else P["inset_hi"])
    a.set(7, 7, P["glow"])
    return a


# ---------------------------------------------------------------- research sources and the analyzer


def observer_front():
    """The machine observer's face: an eye with an amber iris, watching the machine in front of it."""
    a = panel()
    socket(a, 3, 5, 12, 10)
    for x in range(4, 12):
        for y in range(6, 10):
            d = math.hypot((x - 7.5) / 1.6, y - 7.5)
            if d < 2.3:
                a.set(x, y, P["hi"])
    a.rect(7, 6, 8, 9, P["warn"])
    a.rect(7, 7, 8, 8, P["screen"])
    a.set(7, 6, mix(P["warn"], 0.5))
    return a


# ---------------------------------------------------------------- P2P links
P2P_COLOURS = {"item": "#3cc0cc", "fluid": "#4a8ae8", "energy": "#ecaa44", "redstone": "#e05048"}


def p2p_front(kind):
    """The link's working face: two linked nodes joined by a line in the kind's colour."""
    c = hex_(P2P_COLOURS[kind])
    a = panel()
    socket(a, 3, 3, 12, 12)
    a.rect(4, 4, 6, 6, c)
    a.rect(9, 9, 11, 11, c)
    for t in range(6, 10):
        a.set(t, t, mix(c, -0.3))
    a.set(4, 4, mix(c, 0.5))
    a.set(9, 9, mix(c, 0.5))
    return a


# ---------------------------------------------------------------- world devices


def network_chest_front():
    """A chest's two doors with a cyan window: the network shown as slots."""
    a = panel()
    socket(a, 4, 5, 11, 10, P["screen"])
    for gy in range(2):
        for gx in range(3):
            x, y = 5 + gx * 2 + (1 if gx == 2 else 0), 6 + gy * 3
            a.set(x, y, P["acc"] if (gx + gy) % 2 == 0 else dim())
    a.rect(7, 3, 8, 3, P["lo"])
    a.rect(7, 12, 8, 12, P["lo"])
    return a.sym(False)


def network_chest_top():
    a = panel()
    socket(a, 6, 6, 9, 9)
    a.rect(7, 7, 8, 8, P["acc"])
    return a


def collector():
    """A funnel: rings narrowing to a cyan intake, the same on every face."""
    a = panel()
    socket(a, 3, 3, 12, 12, P["lo"])
    socket(a, 5, 5, 10, 10, P["edge"])
    socket(a, 7, 7, 8, 8, P["acc"])
    a.set(7, 7, P["glow"])
    return a


def placer_front():
    """An outlet: a recess with an outward cyan chevron, things leave here."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    for i in range(4):
        a.set(4 + i, 9 - i, P["acc"])
        a.set(4 + i, 10 - i, P["acc_lo"])
    a.rect(4, 11, 11, 11, dim())
    a.rect(7, 6, 8, 6, P["glow"])
    return a.sym(False)


def breaker_front():
    """Two amber jaws across a dark slot: things are taken here."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    amber = P["warn"]
    for y in range(4, 12):
        a.set(4 + abs(y - 7) // 2, y, amber)
        a.set(5 + abs(y - 7) // 2, y, mix(amber, -0.4))
    a.rect(7, 7, 8, 8, mix(amber, 0.4))
    return a.sym(False)


# ---------------------------------------------------------------- controller expansions and status lights


def expansion(kind):
    """Controller expansion side: the casing with a recessed panel showing the module's job, and a dark strip at the
    top and bottom edge where the load overlay lights up."""
    a = panel()
    for y in (1, 14):
        a.rect(2, y, 13, y, P["inset"])
    socket(a, 3, 4, 12, 11)
    if kind == "bandwidth_array":
        for y in (5, 7, 9):
            a.rect(4, y, 11, y, dim())
            a.set(4, y, P["acc"])
            a.set(11, y, P["acc"])
    elif kind == "batch_amplifier":
        for y in (5, 8):
            for k in range(3):
                a.set(7 - k, y + k, dim())
            a.set(7, y, P["acc"])
    elif kind == "device_bus":
        for x in (4, 7, 10):
            for y in (5, 7, 9):
                a.rect(x, y, x + 1, y + 1, dim())
                a.set(x, y, P["acc"])
    else:
        for x, y in EXPANSION_SHAPES[kind]:
            a.set(x, y, dim())
        for x, y in EXPANSION_LIT[kind]:
            a.set(x, y, P["acc"])
    return a.sym()


# Left-half pixels (the side is mirrored) of the area G modules' symbols, drawn dim.
EXPANSION_SHAPES = {
    "efficiency_core": [(7, 4), (6, 5), (5, 6), (4, 7), (4, 8), (5, 9), (6, 10), (7, 11)],
    "surge_regulator": [(7, 4), (6, 5), (5, 6), (6, 7), (7, 8), (6, 9), (5, 10)] + [(x, 11) for x in range(4, 8)],
    "network_anchor": [(7, y) for y in range(5, 11)] + [(5, 6), (6, 6), (4, 9), (5, 10), (6, 11)],
    "alarm_module": [(6, 4), (7, 4), (5, 5), (5, 6), (5, 7), (4, 8), (4, 9)] + [(x, 9) for x in range(5, 8)],
    "antenna_mount": [(7, y) for y in range(6, 12)] + [(4, 5), (5, 6), (6, 7)] + [(x, 11) for x in range(5, 8)],
    "wireless_access_mount": [(4, 6), (5, 5), (6, 4), (7, 4), (5, 8), (6, 7), (7, 7)]
                             + [(x, y) for x in range(6, 8) for y in (10, 11)],
    "telemetry_recorder": [(4, 10), (5, 9), (6, 9), (7, 7)] + [(x, 11) for x in range(4, 8)],
    "security_module": [(5, 5), (5, 6), (6, 4), (7, 4)] + [(x, y) for x in range(4, 8) for y in range(7, 11)],
    "ledger_module": [(7, y) for y in range(4, 12)] + [(x, y) for x in range(4, 7) for y in (5, 7, 9, 11)],
    "dimension_bridge": [(4, y) for y in range(6, 12)] + [(5, 5), (6, 4), (7, 4)]
                        + [(x, y) for x in range(5, 8) for y in (8, 10)],
}

# Left-half pixels of each module's symbol that its load overlay lights: its own lit pattern.
EXPANSION_LIT = {
    "bandwidth_array": [(4, 5), (4, 7), (4, 9)],
    "batch_amplifier": [(7, 5), (7, 8)],
    "device_bus": [(4, 5), (4, 7), (4, 9), (7, 5), (7, 7), (7, 9)],
    "efficiency_core": [(7, 7), (7, 8), (6, 7), (6, 8)],
    "surge_regulator": [(7, 4), (6, 5), (5, 6), (6, 7)],
    "network_anchor": [(7, 4), (4, 9), (6, 11)],
    "security_module": [(7, 8), (7, 9)],
    "telemetry_recorder": [(4, 10), (5, 9), (6, 9), (7, 7), (7, 6)],
    "alarm_module": [(7, 11), (6, 4), (7, 4)],
    "antenna_mount": [(4, 5), (7, 6)],
    "wireless_access_mount": [(6, 4), (7, 4), (7, 7)],
    "ledger_module": [(4, 5), (5, 7), (6, 9)],
    "dimension_bridge": [(6, 4), (7, 4), (6, 8), (7, 10)],
}

EXPANSION_KINDS = ("bandwidth_array", "batch_amplifier", "device_bus", "efficiency_core",
                   "surge_regulator", "network_anchor", "security_module", "telemetry_recorder", "alarm_module",
                   "antenna_mount", "wireless_access_mount", "ledger_module", "dimension_bridge")
# The former expansions that are network devices since M7R item 4: only they light up, and only when active (band 1).
# The retired ones keep their face for old worlds and never glow.
DEVICE_KINDS = ("network_anchor", "security_module", "antenna_mount", "wireless_access_mount", "dimension_bridge")

def expansion_glow(kind, band):
    """Load overlay of an expansion: the edge strips and the module's own symbol lit, brighter with each band."""
    a = Img()
    col = (P["acc_lo"], P["acc"], PEAK)[band - 1]
    for y in (1, 14):
        a.rect(2, y, 7, y, col)
    a.set(2, 1, P["glow"])
    for x, y in EXPANSION_LIT[kind]:
        a.set(x, y, P["glow"] if band == 3 else col)
    return a.sym()


# ---------------------------------------------------------------- materials (ember quartz, data and void crystal)
EMBER = hex_("#e07444")
CRYSTAL = hex_("#6cc8da")
VOID = hex_("#9a6ce0")


def ember_overlay():
    return shaded_overlay(LUMEN, EMBER, mix(EMBER, 0.35), mix(EMBER, -0.4))


def crystal_block(budding, crystal=None):
    c = crystal or CRYSTAL
    a = Img()
    r = rng(31 if budding else 29)
    for y in range(N):
        for x in range(N):
            a.set(x, y, mix(c, -0.3 + (r() - 0.5) * 0.2))
    for i in range(0, N, 4):
        a.rect(i, 0, i, N - 1, mix(c, -0.5))
        a.rect(0, i, N - 1, i, mix(c, -0.5))
    if budding:
        for cx, cy in ((2, 2), (6, 6), (10, 10), (2, 10), (10, 2), (14, 6), (6, 14), (14, 14)):
            a.set(cx, cy, mix(c, 0.5))
    return a.sym(True)


def crystal_stage(size, crystal=None):
    c = crystal or CRYSTAL
    a = Img()
    h = {1: 4, 2: 7, 3: 10, 4: 13}[size]
    for i, cx in enumerate((7, 4, 11)[:1 if size < 3 else 3]):
        hh = h if i == 0 else h - 4
        for y in range(15 - hh, 16):
            for x in (cx, cx + 1):
                a.set(x, y, mix(c, 0.3 if x == cx else -0.2))
        a.set(cx, 15 - hh, mix(c, 0.7))
    return a


# ---------------------------------------------------------------- signal (antennas, wireless terminal)


def antenna(tier):
    """An antenna's faces: a mast in a recess, one lit ring per tier around its tip."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    a.rect(7, 5, 7, 11, P["lo"])
    a.rect(5, 11, 7, 11, P["lo"])
    for ring in range(tier):
        y = 7 - 2 * ring
        a.set(5 + ring // 2, y, P["acc"])
        a.set(6, y - 1, P["acc_lo"])
    a.set(7, 4, P["glow"])
    return a.sym()


# ---------------------------------------------------------------- network tools


# ---------------------------------------------------------------- server tools


# ---------------------------------------------------------------- network services, bulk drive, stock keeping


def mail_port_front():
    """A letter slot over a small outbox window: mail leaves through the slot."""
    a = panel()
    socket(a, 4, 4, 11, 4)
    a.set(7, 4, P["acc"])
    a.set(8, 4, P["acc"])
    socket(a, 4, 8, 11, 12, P["screen"])
    for x, y in ((5, 9), (6, 10), (7, 11), (8, 11), (9, 10), (10, 9)):
        a.set(x, y, P["acc"])
    return a


def bulk_drive_front():
    """One wide bay instead of the drive's eight slots, a fill gauge down its middle: one kind, held in bulk."""
    a = panel()
    socket(a, 3, 2, 12, 13)
    for y in range(4, 12):
        a.rect(5, y, 10, y, P["inset_hi"] if y % 2 else P["inset"])
    a.rect(7, 4, 8, 11, dim())
    a.rect(7, 8, 8, 11, P["acc"])
    a.set(7, 8, P["glow"])
    return a


# ---------------------------------------------------------------- autocrafting (CPU, assembler, patterns, provider)
CPU_TIER = {1: P["warn"], 2: P["acc"], 3: hex_("#b080f0")}


def cpu_core(tier):
    """A crafting CPU core: a recess with a square ring in the tier's colour and one to three tier pips below."""
    c = CPU_TIER[tier]
    a = panel()
    socket(a, 4, 4, 11, 11)
    for t in range(5, 11):
        for (x, y) in ((t, 5), (t, 10), (5, t), (10, t)):
            a.set(x, y, c)
    a.rect(7, 7, 8, 8, mix(c, 0.55))
    xs = {1: (7,), 2: (6, 9), 3: (5, 7, 10)}[tier]
    for x in xs:
        a.set(x if tier != 3 or x != 7 else 7, 13, c)
    if tier == 3:
        a.set(8, 13, c)
    return a


def cpu_cache():
    a = panel()
    socket(a, 3, 3, 12, 12)
    for y in range(4, 12, 2):
        for x in range(4, 12, 2):
            a.set(x, y, P["acc"] if (x + y) % 4 == 0 else P["acc_lo"])
    return a


def cpu_lane():
    a = panel()
    socket(a, 3, 3, 12, 12)
    for x in (5, 7, 8, 10):
        a.rect(x, 4, x, 11, P["acc_lo"] if x in (5, 10) else P["acc"])
    return a


def cpu_storage():
    a = panel()
    socket(a, 3, 3, 12, 12)
    for y in (5, 8, 11):
        a.rect(4, y - 1, 11, y - 1, P["inset_hi"])
        a.rect(4, y, 9, y, P["acc_lo"])
        a.set(11, y, P["ok"])
    return a


def crafting_assembler():
    """A recessed 3 x 3 crafting grid with a lit centre."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    for gy in range(3):
        for gx in range(3):
            x, y = 4 + gx * 3, 4 + gy * 3
            a.rect(x, y, x + 1, y + 1, P["acc"] if (gx, gy) == (1, 1) else P["lo"])
            a.set(x, y, P["glow"] if (gx, gy) == (1, 1) else P["hi"])
    return a


def logic_controller():
    """A dark screen with a tiny node graph: two nodes joined by an amber wire."""
    a = panel()
    socket(a, 3, 4, 12, 11, P["screen"])
    a.rect(4, 5, 6, 7, P["acc"])
    a.set(4, 5, P["glow"])
    a.rect(9, 8, 11, 10, P["acc"])
    a.set(9, 8, P["glow"])
    for x in range(7, 10):
        a.set(x, 6, P["wire"])
    for y in range(6, 8):
        a.set(9, y, P["wire"])
    a.rect(4, 10, 5, 10, P["ok"])
    return a


def sensor_front():
    """The sensor's lens: rings narrowing to a dark pupil."""
    a = panel()
    socket(a, 4, 4, 11, 11)
    a.rect(5, 6, 10, 9, P["acc_lo"])
    a.rect(6, 5, 9, 10, P["acc_lo"])
    a.rect(6, 6, 9, 9, P["acc"])
    a.rect(7, 7, 8, 8, P["screen"])
    a.set(6, 6, P["glow"])
    return a


def pattern_provider_face():
    """Pattern sheets pushed forward: a stack of two sheets with cyan lines in a recess."""
    a = panel()
    socket(a, 3, 3, 12, 12)
    a.rect(4, 6, 9, 11, P["lo"])
    a.rect(6, 4, 11, 9, hex_("#e4dfd0"))
    a.rect(6, 4, 11, 4, P["hi"])
    for y in (6, 8):
        a.rect(7, y, 10, y, P["acc_lo"])
    a.set(7, 6, P["acc"])
    return a


# ---------------------------------------------------------------- registry of outputs
# The facing sides are mtx_devices.py's (MTX item 5b) and mtx_storage_blocks.py's (item 5a), port/side mtx_ports.py's.


def outputs():
    o = {}
    # The plain casing and side textures are texture_kit.py's (MTX item 1); this script no longer writes them.
    # Terminal, screen and display faces are mtx_screens.py's (MTX item 3).
    # The device textures are mtx_devices.py's (MTX item 5b).

    def add(path, img):
        o[path] = img

    add("block/lumen_quartz_ore_overlay", lumen_overlay())
    add("block/tantalite_ore_overlay", tantalite_overlay())

    add("block/cable/sleeve", cable_sleeve())
    add("block/cable/sleeve_core", cable_sleeve_core())
    add("block/cable/covered", cable_covered())
    add("block/cable/covered_core", cable_covered_core())
    for tier in (1, 2, 3, 4):
        add(f"block/cable/covered_core_tier_{tier}", cable_covered_core(tier))
    add("block/cable/lane", cable_lane(False))
    add("block/cable/fiber_lane", cable_lane(True))
    for tier in (1, 2, 3, 4):
        add(f"block/cable/lane_core_tier_{tier}", cable_lane_core(tier))
    # The port textures (side, stub_end, fronts, steps) are mtx_ports.py's (MTX item 4).
    add("block/ember_quartz_ore_overlay", ember_overlay())
    add("block/data_crystal_block", crystal_block(False))
    add("block/budding_data_crystal", crystal_block(True))
    add("block/void_crystal_block", crystal_block(False, VOID))
    add("block/budding_void_crystal", crystal_block(True, VOID))
    for size, stem in ((1, "small_{}_crystal_bud"), (2, "medium_{}_crystal_bud"), (3, "large_{}_crystal_bud"),
                       (4, "{}_crystal_cluster")):
        add("block/" + stem.format("data"), crystal_stage(size))
        add("block/" + stem.format("void"), crystal_stage(size, VOID))
    return o


def write(out, o):
    for path, v in o.items():
        f = out / (path + ".png")
        f.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(v, tuple):
            frames = v[1]
            strip = Image.new("RGBA", (N, N * len(frames)), (0, 0, 0, 0))
            for i, fr in enumerate(frames):
                strip.paste(fr.image(), (0, i * N))
            strip.save(f)
        else:
            v.image().save(f)


def sheet(o, path, sc=8, cols=12):
    names = sorted(o)
    cw, ch = N * sc + 8, N * sc + 22
    rows = (len(names) + cols - 1) // cols
    im = Image.new("RGBA", (cols * cw, rows * ch), (58, 60, 66, 255))
    d = ImageDraw.Draw(im)
    for i, n in enumerate(names):
        v = o[n]
        t = (v[1][0] if isinstance(v, tuple) else v).image().resize((N * sc, N * sc), Image.NEAREST)
        x, y = (i % cols) * cw + 4, (i // cols) * ch + 2
        d.rectangle([x - 1, y - 1, x + N * sc, y + N * sc], outline=(88, 90, 96, 255))
        im.paste(t, (x, y), t)
        d.text((x, y + N * sc + 3), n.split("/", 1)[1][-20:], fill=(220, 220, 224, 255))
    im.save(path)


def anim_sheet(o, path, sc=4):
    anims = [(n, v[1]) for n, v in sorted(o.items()) if isinstance(v, tuple)]
    w = max(len(f) for _, f in anims) * (N * sc + 4)
    im = Image.new("RGBA", (w, len(anims) * (N * sc + 18)), (58, 60, 66, 255))
    d = ImageDraw.Draw(im)
    for r, (n, frames) in enumerate(anims):
        y = r * (N * sc + 18)
        d.text((2, y + 1), n.split("/", 1)[1], fill=(220, 220, 224, 255))
        for i, fr in enumerate(frames):
            t = fr.image().resize((N * sc, N * sc), Image.NEAREST)
            im.paste(t, (i * (N * sc + 4), y + 14), t)
    im.save(path)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--sheet", help="directory for the contact sheets")
    args = ap.parse_args()
    out = outputs()
    write(Path(args.out), out)
    if args.sheet:
        sd = Path(args.sheet)
        sd.mkdir(parents=True, exist_ok=True)
        sheet(out, sd / "contact-sheet.png")
        anim_sheet(out, sd / "animations.png")
    print(len(out), "textures")
