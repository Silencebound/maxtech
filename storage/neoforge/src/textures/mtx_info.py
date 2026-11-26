#!/usr/bin/env python3
"""Overlay icons for Jade's compact view (B09 item 7, owner 2026-10-17), 8 x 8 GUI sprites in the 2a palette.

- ``info/status_<tone>``: the status dot, a round 2a pill with the dark frame, a light top-left pixel and a darker
  bottom-right edge. Tones and colours match the front line and Lib's ``BlockInfoSink.Tone``: ``good`` the Storage
  accent, ``warn`` amber, ``bad`` red, ``dim`` grey (the Lib accent, since the front line's offline grey is too dark on
  an overlay).
- ``info/port_import`` / ``info/port_export``: a cyan arrow down onto (import, into the network) or up from (export) a
  grey bar.
- ``info/port_storage``: a cyan ring with a grey core over the grey bar (a port in storage mode).
- ``info/switch_on`` / ``info/switch_off``: a framed track with the knob right and cyan (on) or left and grey (off).
- ``info/channels``: three cyan lanes between grey ends, before a channel bar.
- Lib's ``info/energy`` (written to Lib's textures, ``maxtech_lib:info/energy``): a yellow bolt before Lib's
  stored-energy bar; drawn here so every overlay icon comes from one script.

Output lands under ``textures/gui/sprites/info/`` (the GUI atlas stitches it; sprite id ``maxtech_storage:info/<name>``).
No ``.mcmeta``: stretched, drawn at their size. ``--check`` writes nothing and exits 1 if a committed PNG differs
(``TextureKitTest`` runs it).

Usage: python mtx_info.py [--check] [--list]
"""
import argparse
import sys

from texture_kit import ACCENTS, FRAME, INSET, ROOT, TEXTURES, Canvas, hex_, matches, shade, state_colour, write_png

S = 8
TONES = {
    "good": ACCENTS["storage"],
    "warn": state_colour(ACCENTS["storage"], "warning"),
    "bad": state_colour(ACCENTS["storage"], "error"),
    "dim": ACCENTS["lib"],
}
BAR = hex_("#9aa1aa")

# The dot's rows: (first x, last x) of each row; the outermost pixels of every row and of the top and bottom rows are
# the frame.
DOT_ROWS = ((2, 5), (1, 6), (0, 7), (0, 7), (0, 7), (0, 7), (1, 6), (2, 5))


def dot(colour):
    c = Canvas(S, S)
    for y, (x0, x1) in enumerate(DOT_ROWS):
        for x in range(x0, x1 + 1):
            edge = x in (x0, x1) or y in (0, S - 1) or (y == 1 and x in (x0 + 1, x1 - 1)) \
                or (y == S - 2 and x in (x0 + 1, x1 - 1))
            c.put(x, y, FRAME if edge else colour)
    for y in range(1, S - 1):
        for x in range(1, S - 1):
            p = c.get(x, y)
            if p[3] and p[:3] == colour and (c.get(x + 1, y)[:3] == FRAME or c.get(x, y + 1)[:3] == FRAME):
                c.put(x, y, shade(colour, 0.75))
    c.put(2, 2, shade(colour, 1.35))
    return c


def arrow(up):
    c = Canvas(S, S)
    accent = ACCENTS["storage"]
    c.rect(1, 7, 6, 7, BAR)
    if up:
        c.rect(3, 0, 4, 0, accent)
        c.rect(2, 1, 5, 1, accent)
        c.rect(1, 2, 6, 2, accent)
        c.rect(3, 3, 4, 5, accent)
    else:
        c.rect(3, 0, 4, 2, accent)
        c.rect(1, 3, 6, 3, accent)
        c.rect(2, 4, 5, 4, accent)
        c.rect(3, 5, 4, 5, accent)
    return c


def storage_box():
    """A storage bus: a cyan ring with a grey core over the grey bar, the inventory mounted in the network."""
    c = Canvas(S, S)
    accent = ACCENTS["storage"]
    c.rect(1, 7, 6, 7, BAR)
    for i in range(1, 7):
        for x, y in ((i, 0), (i, 5), (1, i - 1), (6, i - 1)):
            c.put(x, y, accent)
    c.rect(3, 2, 4, 3, BAR)
    return c


def switch(on):
    c = Canvas(S, S)
    c.rect(1, 2, 6, 2, FRAME)
    c.rect(1, 5, 6, 5, FRAME)
    c.rect(0, 3, 0, 4, FRAME)
    c.rect(7, 3, 7, 4, FRAME)
    c.rect(1, 3, 6, 4, INSET)
    knob = ACCENTS["storage"] if on else BAR
    x0 = 4 if on else 1
    c.rect(x0, 1, x0 + 2, 6, knob)
    c.put(x0, 1, shade(knob, 1.25))
    c.rect(x0, 6, x0 + 2, 6, shade(knob, 0.7))
    return c


def channels():
    """Three short cyan lanes between grey ends: a cable's channels."""
    c = Canvas(S, S)
    c.rect(0, 1, 0, 6, BAR)
    c.rect(7, 1, 7, 6, BAR)
    for y in (1, 3, 5):
        c.rect(1, y, 6, y + 1, ACCENTS["storage"] if y != 5 else shade(ACCENTS["storage"], 0.6))
        c.put(1, y + 1, shade(ACCENTS["storage"], 0.45))
        c.rect(2, y + 1, 6, y + 1, FRAME)
    return c


def bolt():
    """A yellow lightning bolt with a dark outline: stored energy."""
    c = Canvas(S, S)
    yellow = hex_("#ffd23f")
    body = ((4, 0), (5, 0), (3, 1), (4, 1), (2, 2), (3, 2), (2, 3), (3, 3), (4, 3), (5, 3), (4, 4), (5, 4),
            (3, 5), (4, 5), (3, 6), (2, 7))
    for x, y in body:
        c.put(x, y, yellow)
    for x, y in body:
        for dx, dy in ((1, 0), (0, 1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < S and 0 <= ny < S and c.get(nx, ny)[3] == 0:
                c.put(nx, ny, FRAME)
    c.put(4, 0, shade(yellow, 1.2))
    return c


def outputs():
    """(path relative to textures/, canvas) for every texture this script writes; ``lib/`` paths go to Lib."""
    out = [(f"gui/sprites/info/status_{tone}", dot(colour)) for tone, colour in TONES.items()]
    out.append(("gui/sprites/info/port_import", arrow(False)))
    out.append(("gui/sprites/info/port_export", arrow(True)))
    out.append(("gui/sprites/info/port_storage", storage_box()))
    out.append(("gui/sprites/info/switch_on", switch(True)))
    out.append(("gui/sprites/info/switch_off", switch(False)))
    out.append(("gui/sprites/info/channels", channels()))
    out.append(("lib/gui/sprites/info/energy", bolt()))
    return out


LIB_TEXTURES = ROOT.parent / "lib/common/src/main/resources/assets/maxtech_lib/textures"


def target(path):
    """The PNG file of ``path``: Lib's textures for ``lib/`` paths (Lib's energy entries draw them), else Storage's."""
    if path.startswith("lib/"):
        return LIB_TEXTURES / (path[len("lib/"):] + ".png")
    return TEXTURES / (path + ".png")


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
        bad = [path for path, c in items
               if not target(path).is_file() or not matches(target(path), c)]
        for path in bad:
            print("differs from mtx_info.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_info.py")
            return 1
        print(len(items), "overlay icons match")
        return 0
    for path, c in items:
        write_png(target(path), c)
    print(len(items), "overlay icons")
    return 0


if __name__ == "__main__":
    sys.exit(main())
