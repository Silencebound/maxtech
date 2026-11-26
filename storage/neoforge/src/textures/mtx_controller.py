#!/usr/bin/env python3
"""Draws the storage controller's faces in the family style 2a (MTX item 2, docs/milestones/MTX.md).

The pixel rules are ``aeTile()`` on the family style 2a page, the
AE2-style controller the owner picked: the runic sigil (design 1) on every face on a darker plate, a 1 px frame only on
the open edges (those that do not touch another controller face), short sigil lines across the middle of every
connected edge so the sigils meet between blocks, and the 8 px accent line on an open bottom edge. Drawn from scratch;
no AE2 art.

The connected-texture model from M7R item 7 (``ControllerFaceModel``) stays: emission is per quad, so every face is a
cutout plate on the block face with a full-bright glow plane behind it.

* ``block/controller/face/plate_<mask>``: the plate. Bit 1 top, 2 right, 4 bottom, 8 left set where the edge is open
  (framed), so ``plate_15`` is a lone block's face. Cut out (alpha 0): the sigil, the accent line on an open bottom
  edge, and the two middle pixels of every connected edge.
* ``block/controller/face/glow_<status>_<phase>``: the glow plane seen through the cuts, a 16-frame strip; phase ``p``
  starts ``2 p`` frames later, so faces further from the multiblock's centre light up later. The wave runs outward from
  the face's centre (``aeTile``'s ``cos(d * .35 - t)``); the line pixels on all four edges glow with it, brighter, as
  the page's accent line. Outside the cuts the plane repeats the plate. Off is one still ``glow_off``: a dim etched
  sigil and the kit's grey ``off`` line.

The still copies (first frame, no .mcmeta) go to the ``still_effects`` pack, as for every animated Storage strip. Only
the controller animates (MTX "Decided").

Plain Python 3 on ``texture_kit`` (no Pillow). ``--check`` writes nothing and exits 1 if a committed sprite, .mcmeta
or still copy differs from what this script draws (``TextureKitTest`` runs it).

Usage: python mtx_controller.py [--check] [--list] [--sheet FILE]
"""
import argparse
import json
import math
import sys

from texture_kit import (ACCENTS, FRAME, N, ROOT, TEXTURES, Canvas, hex_, matches, shade, state_colour,
                         write_png)

STILL = ROOT / "common/src/main/resources/resourcepacks/still_effects/assets/maxtech_storage/textures"
FOLDER = "block/controller/face"

FRAMES = 16
PHASES = 8
QUAD = ["##..#..", "#...#..", "..###.#", "....#.#", "###.#..", "..#...#", "..#.###"]
PLATE = hex_("#1c2026")
PLATE2 = hex_("#22272e")
TOP, RIGHT, BOTTOM, LEFT = 1, 2, 4, 8
# Glow colour per status and frametime in game ticks per frame (0: still).
STATUS = {
    "online": (ACCENTS["storage"], 2),
    "error": (state_colour(ACCENTS["storage"], "error"), 3),
}


def mix(a, b, f):
    return tuple(int(x + (y - x) * f + 0.5) for x, y in zip(a, b))


def plate_colour(x, y):
    return PLATE2 if (x * 7 + y * 3) % 13 == 0 else PLATE


def groove(x, y):
    mx, my = min(x, 15 - x), min(y, 15 - y)
    return mx >= 1 and my >= 1 and QUAD[my - 1][mx - 1] == "#"


def accent_pixel(x, y):
    """The 8 px accent line on the bottom edge (x 4 to 11), mirrored to the top so the glow plane stays symmetric."""
    return y in (0, N - 1) and 4 <= x <= 11


def joint(x, y, mask):
    """The two middle pixels of a connected edge, where the sigil lines meet the neighbour's."""
    return ((y == 0 and x in (7, 8) and not mask & TOP) or (y == N - 1 and x in (7, 8) and not mask & BOTTOM)
            or (x == 0 and y in (7, 8) and not mask & LEFT) or (x == N - 1 and y in (7, 8) and not mask & RIGHT))


def lit(x, y):
    """Pixels the glow plane lights: the sigil, the accent line rows and the edge joints."""
    return groove(x, y) or accent_pixel(x, y) or (x in (0, N - 1) and y in (7, 8))


def open_edge(x, y, mask):
    return ((y == 0 and mask & TOP) or (x == N - 1 and mask & RIGHT) or (y == N - 1 and mask & BOTTOM)
            or (x == 0 and mask & LEFT))


def plate(mask):
    c = Canvas()
    for y in range(N):
        for x in range(N):
            if groove(x, y) or joint(x, y, mask) or (y == N - 1 and mask & BOTTOM and accent_pixel(x, y)):
                c.put(x, y, plate_colour(x, y), 0)
            elif open_edge(x, y, mask):
                c.put(x, y, FRAME)
            else:
                c.put(x, y, plate_colour(x, y))
    return c


def glow_frame(status, frame):
    c = Canvas()
    theta = 2 * math.pi * frame / FRAMES
    for y in range(N):
        for x in range(N):
            if not lit(x, y):
                c.put(x, y, plate_colour(x, y))
                continue
            if status == "off":
                line = state_colour(ACCENTS["storage"], "off")
                c.put(x, y, mix(PLATE, shade(ACCENTS["storage"], 0.4), 0.5) if groove(x, y) else line)
                continue
            accent = STATUS[status][0]
            d = abs(x - 7.5) + abs(y - 7.5)
            k = max(0.0, math.cos(d * 0.35 - theta)) ** 2
            if groove(x, y):
                c.put(x, y, mix(shade(accent, 0.4), accent, 0.25 + 0.75 * k))
            else:
                c.put(x, y, mix(shade(accent, 0.6), accent, 0.55 + 0.45 * k))
    return c


def glow_strip(status, phase):
    """Phase ``phase`` starts 2 * phase frames back, so its crests arrive that much later (further out)."""
    strip = Canvas(N, N * FRAMES)
    for i in range(FRAMES):
        f = glow_frame(status, (i - 2 * phase) % FRAMES)
        for y in range(N):
            for x in range(N):
                strip.px[(i * N + y) * N + x] = f.get(x, y)
    return strip


def first_frame(c):
    out = Canvas()
    out.px = c.px[:N * N]
    return out


def meta(frametime):
    return json.dumps({"animation": {"frametime": frametime, "interpolate": True}}, indent=2) + "\n"


def outputs():
    """(path relative to textures/, canvas, frametime or None) for every sprite."""
    out = [(f"{FOLDER}/plate_{mask}", plate(mask), None) for mask in range(16)]
    for status, (_, frametime) in STATUS.items():
        for phase in range(PHASES):
            out.append((f"{FOLDER}/glow_{status}_{phase}", glow_strip(status, phase), frametime))
    out.append((f"{FOLDER}/glow_off", glow_frame("off", 0), None))
    return out


def write(items):
    for path, c, frametime in items:
        f = TEXTURES / (path + ".png")
        write_png(f, c)
        if frametime is not None:
            f.with_name(f.name + ".mcmeta").write_bytes(meta(frametime).encode("utf-8"))
            write_png(STILL / (path + ".png"), first_frame(c))


def check(items):
    bad = []
    for path, c, frametime in items:
        f = TEXTURES / (path + ".png")
        if not f.is_file() or not matches(f, c):
            bad.append(path)
        m = f.with_name(f.name + ".mcmeta")
        if frametime is None:
            if m.exists():
                bad.append(path + ".png.mcmeta (still sprite)")
        else:
            if not m.is_file() or m.read_bytes().replace(b"\r\n", b"\n") != meta(frametime).encode("utf-8"):
                bad.append(path + ".png.mcmeta")
            still = STILL / (path + ".png")
            if not still.is_file() or not matches(still, first_frame(c)):
                bad.append("still_effects " + path)
    return bad


def sheet(items, path, sc=8):
    """Every plate, then frame 0 of every glow sprite, scaled up."""
    cols, cell = 16, N * sc + 4
    rows = (len(items) + cols - 1) // cols
    out = Canvas(cols * cell, rows * cell, (58, 60, 66, 255))
    for i, (_, c, _) in enumerate(items):
        for y in range(N * sc):
            for x in range(N * sc):
                p = c.get(x // sc, y // sc)
                if p[3]:
                    out.put((i % cols) * cell + x, (i // cols) * cell + y, p[:3])
    write_png(path, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed sprites with this script")
    ap.add_argument("--sheet", help="write a contact sheet to this file instead of the sprites")
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
            print("differs from mtx_controller.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_controller.py")
            return 1
        print(len(items), "controller face sprites match")
        return 0
    if args.sheet:
        sheet(items, args.sheet)
        return 0
    write(items)
    print(len(items), "controller face sprites")
    return 0


if __name__ == "__main__":
    sys.exit(main())
