#!/usr/bin/env python3
"""Importer and exporter textures in the family style 2a (MTX item 4; plain blocks 2026-10-17, docs/milestones/MTX.md).

The port blocks are plain full cubes (owner, 2026-10-17); the cable parts keep shape A (``PortShapes``): three square
steps, each 2 px deep, a pyramid for the exporter and a funnel for the importer, only the smallest step cyan.

- ``front_import`` / ``front_export`` (and ``_tier_2`` / ``_tier_3``): the block's front, the kit base with the online
  (bright) line, the ports' only accent line (fronts only; in the world it shows the port's network state, see
  ``LinkLook``), and a dark screen (3 to 12) with four cyan arrows, one per
  edge, pointing outwards for the exporter and inwards for the importer, round a 2 x 2 centre (cyan for the exporter,
  grey for the importer). From tier 2 the tier's pips (two or three) sit in the top frame row.
- ``front_storage`` (and tiers): the storage bus (B09 item 8) on the same base: a cyan ring round a grey core and a
  cyan dot in each screen corner, no arrows.
- ``side``: a plain kit side without the line (the port block's four sides, also the side of several cable parts);
  ``stub_end``: the back, where the port links to the network, a dark connector in the inset (also the back of every
  directional device).
- ``part_step`` (and tiers): a part's grey steps, drawn one texel per pixel, so each step shows its own crop: a framed
  ring with a bevel at the outline of the 10 px and the 6 px step, the tier pips in the 10 px ring's top row. The thin
  sides of every step take the plain inset in the top-left corner. ``step_tip``: the cyan tip, one flat colour.

No ``.mcmeta``: every port texture is static. ``--check`` writes nothing and exits 1 if a committed PNG differs
(``TextureKitTest`` runs it).

Usage: python mtx_ports.py [--check] [--list]
"""
import argparse
import sys

from texture_kit import (ACCENTS, BEVEL_DARK, BEVEL_LIGHT, FRAME, INSET, N, SCREEN, TEXTURES, Canvas, base,
                         matches, state_colour, write_png)

ACCENT = ACCENTS["storage"]
PIP = ACCENTS["lib"]
STATE = "idle"


# One arrow's pixels for the top edge; ``arrows`` turns them to all four edges. The exporter's tip is at the edge
# (pointing out), the importer's at the centre side (pointing in).
EXPORT_ARROW = ((7, 3), (8, 3), (6, 4), (9, 4))
IMPORT_ARROW = ((6, 3), (9, 3), (7, 4), (8, 4))


def arrows(c, base_set, colour):
    """Each (x, y) of ``base_set`` four times: as given, mirrored to the bottom edge and turned to the two sides."""
    for x, y in base_set:
        for px, py in ((x, y), (x, N - 1 - y), (y, x), (N - 1 - y, x)):
            c.put(px, py, colour)


def front(tier, mode):
    """The block's front in ``mode`` ("import", "export" or "storage"); tier 1 has no pips."""
    c = Canvas()
    # The online line: the world draws the port's no channel or offline line over it (LinkLook); items show this.
    base(c, ACCENT, "working", line=True)
    c.rect(3, 3, 12, 12, SCREEN)
    if mode == "storage":
        storage_glyph(c)
    else:
        export = mode == "export"
        arrows(c, EXPORT_ARROW if export else IMPORT_ARROW, ACCENT)
        c.rect(7, 7, 8, 8, ACCENT if export else BEVEL_LIGHT)
    for x in pips(tier, 2):
        c.put(x, 0, PIP)
        c.put(x + 1, 0, PIP)
    return c


def storage_glyph(c):
    """The storage bus's screen: a cyan ring (5 to 10) round a grey 2 x 2 core, and a cyan dot in each corner of the
    screen, pointing neither in nor out: the inventory is part of the network."""
    for i in range(5, 11):
        for x, y in ((i, 5), (i, 10), (5, i), (10, i)):
            c.put(x, y, ACCENT)
    c.rect(7, 7, 8, 8, BEVEL_LIGHT)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        c.put(x, y, ACCENT)


def pips(tier, gap):
    """Left x of each 2 px pip, centred on the texture: none for tier 1, two (2 px apart) or three (``gap`` apart)."""
    if tier == 2:
        return (5, 9)
    if tier == 3:
        return (5 - gap, 7, 9 + gap)
    return ()


def side():
    c = Canvas()
    base(c, ACCENT, STATE, line=False)
    return c


def stub_end():
    """The connector on the back: a dark 6 x 6 socket with clipped corners and a dim cyan core."""
    c = Canvas()
    base(c, ACCENT, STATE, line=False)
    c.rect(5, 5, 10, 10, FRAME)
    for x, y in ((5, 5), (10, 5), (5, 10), (10, 10)):
        c.put(x, y, INSET)
    c.rect(6, 6, 9, 9, SCREEN)
    c.rect(7, 7, 8, 8, state_colour(ACCENT, STATE))
    return c


def ring(c, lo, hi):
    """A step's outline: frame on the box's edge pixels lo and hi, bevel one pixel inside (light top-left)."""
    for i in range(lo, hi + 1):
        for x, y in ((i, lo), (i, hi), (lo, i), (hi, i)):
            c.put(x, y, FRAME)
    for i in range(lo + 1, hi):
        c.put(i, hi - 1, BEVEL_DARK)
        c.put(hi - 1, i, BEVEL_DARK)
    for i in range(lo + 1, hi):
        c.put(i, lo + 1, BEVEL_LIGHT)
        c.put(lo + 1, i, BEVEL_LIGHT)


def steps(sizes, tier=1):
    """The grey steps of ``sizes`` (the tip excluded), largest first; pips in the largest ring's top frame row."""
    c = Canvas(fill=INSET + (255,))
    for size in sizes:
        lo = (16 - size) // 2
        ring(c, lo, lo + size - 1)
    top = (16 - sizes[0]) // 2
    for x in pips(tier, 1):
        c.put(x, top, PIP)
        c.put(x + 1, top, PIP)
    return c


def tip():
    return Canvas(fill=ACCENT + (255,))


def outputs():
    """(path relative to textures/, canvas) for every texture this script writes."""
    out = []
    for tier in (1, 2, 3):
        suffix = "" if tier == 1 else f"_tier_{tier}"
        out.append((f"block/port/front_import{suffix}", front(tier, "import")))
        out.append((f"block/port/front_export{suffix}", front(tier, "export")))
        out.append((f"block/port/front_storage{suffix}", front(tier, "storage")))
        out.append((f"block/port/part_step{suffix}", steps((10, 6), tier)))
    out.append(("block/port/side", side()))
    out.append(("block/port/stub_end", stub_end()))
    out.append(("block/port/step_tip", tip()))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed PNGs, write nothing")
    ap.add_argument("--list", action="store_true", help="print the texture paths this script owns, write nothing")
    args = ap.parse_args()
    items = outputs()
    if args.list:
        for item in items:
            print(item[0])
        return 0
    if args.check:
        bad = []
        for path, c in items:
            f = TEXTURES / (path + ".png")
            if not f.is_file() or not matches(f, c):
                bad.append(path)
            if f.with_name(f.name + ".mcmeta").exists():
                bad.append(path + ".png.mcmeta (ports are static)")
        for path in bad:
            print("differs from mtx_ports.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_ports.py")
            return 1
        print(len(items), "port textures match")
        return 0
    for path, c in items:
        write_png(TEXTURES / (path + ".png"), c)
    print(len(items), "port textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
