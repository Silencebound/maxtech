#!/usr/bin/env python3
"""MaxTech family texture kit, style 2a "inset panel" (owner decision 2026-10-16, docs/milestones/MTX.md).

The base every MaxTech block face starts from, ported pixel for pixel from ``base()`` on the family style 2a page:
a 1 px dark frame with a light speckle, a 14 x 14 inset one step lighter with its own speckle, a 1 px bevel (light top and left, dark bottom and right) and, on a front only, an 8 px
accent line centred in the bottom frame row, coloured by the block's state. Faces with motifs (later MTX items) draw
the base, then their motif inside the rectangle ``base`` returns.

The accent line goes only on a block's front, the face it points with (owner, 2026-10-17): sides, backs, tops, bottoms
and the faces of blocks without a facing get a plain frame row. Every caller passes ``line`` explicitly, decided by the
model face the texture is drawn on; a texture used both as a front and elsewhere counts as a front. The controller
(``mtx_controller.py``, not drawn on ``base``) keeps its state line on every face.

Used as a library by the other generators::

    from texture_kit import Canvas, base, write_png, ACCENTS, SCREEN

    c = Canvas()
    x0, y0, x1, y1 = base(c, ACCENTS["storage"], state="working", line=True)  # inner rectangle, inside the bevel
    c.rect(x0, y0, x1, y1, SCREEN)                                   # the motif
    write_png(TEXTURES / "block/foo.png", c)

``base`` takes ``conn=(top, right, bottom, left)`` for connected textures: a side whose flag is true has no frame and
no bevel (the inset runs to the edge) and, for the bottom, no accent line, so neighbouring faces merge.

Run as a script it writes the plain casing and side textures (``CASING`` below) and the front line overlays of the
network states (``LINK_LINES``) and nothing else, so the other
generators' output stays untouched. ``--check`` writes nothing and exits 1 if a committed PNG's pixels differ from
what the kit draws (Storage's ``TextureKitTest`` runs it when Python is installed). ``--sheet FILE`` writes a contact
sheet of every state and accent instead.

Plain Python 3, no Pillow: the PNG writer below is stdlib only (zlib, filter 0, level 9), so the same input gives the
same bytes, and the check runs on any machine with Python. The check compares decoded pixels, not bytes, so a zlib
build that compresses differently does not fail it.

Usage: python texture_kit.py [--check] [--list] [--sheet FILE]
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

N = 16
ROOT = Path(__file__).resolve().parents[3]
TEXTURES = ROOT / "common/src/main/resources/assets/maxtech_storage/textures"


def hex_(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def shade(c, f):
    """The page's ``sh``: every channel times f, rounded half up like JavaScript's Math.round, clamped."""
    return tuple(max(0, min(255, int(v * f + 0.5))) for v in c)


# The shared palette (MTX "Decided").
FRAME = hex_("#1a1d21")
FRAME2 = hex_("#25292f")
INSET = hex_("#353a42")
INSET2 = hex_("#3a3f48")
BEVEL_LIGHT = hex_("#4c535d")
BEVEL_DARK = hex_("#2a2e34")
SCREEN = hex_("#16191d")
ACCENTS = {
    "storage": hex_("#3fc3e0"),
    "farms": hex_("#5fe08a"),
    "machines": hex_("#f0a03a"),
    "rooms": hex_("#b07ae8"),
    "lib": hex_("#9aa1aa"),
}
STATES = ("off", "idle", "working", "warning", "error")


def state_colour(accent, state):
    """The accent line's colour: off grey, idle the accent at 45 %, working the accent, warning amber, error red."""
    if state == "off":
        return hex_("#2e3238")
    if state == "idle":
        return shade(accent, 0.45)
    if state == "working":
        return accent
    if state == "warning":
        return hex_("#f0aa3c")
    if state == "error":
        return hex_("#e05a5a")
    raise ValueError("unknown state " + state)


class Canvas:
    """A w x h RGBA image as a flat list of tuples; opaque unless a pixel is set with an alpha."""

    def __init__(self, w=N, h=N, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [fill] * (w * h)

    def put(self, x, y, c, a=255):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = (c[0], c[1], c[2], a)

    def get(self, x, y):
        return self.px[y * self.w + x]

    def rect(self, x0, y0, x1, y1, c, a=255):
        """Fills the rectangle with corners (x0, y0) and (x1, y1), both inclusive."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c, a)


def base(c, accent, state="working", conn=(False, False, False, False), *, line):
    """Draws the 2a base on the 16 x 16 canvas ``c`` and returns the inner rectangle (x0, y0, x1, y1), inclusive,
    inside the bevel, where a motif goes.

    ``accent`` is an RGB tuple (usually ``ACCENTS[mod]``), ``state`` one of ``STATES``. ``conn`` is
    (top, right, bottom, left): true where the face continues into a neighbour, so that side gets no frame, bevel or
    accent line. ``line`` (required) is true only for a front: it draws the accent line in the state's colour; false
    leaves the bottom frame row plain, and ``state`` then changes nothing.
    """
    ct, cr, cb, cl = conn
    lo, hi = 1, N - 2
    for y in range(N):
        for x in range(N):
            frame = (not cl and x < lo) or (not ct and y < lo) or (not cr and x > hi) or (not cb and y > hi)
            if frame:
                c.put(x, y, FRAME2 if (x * 3 + y * 5) % 7 == 0 else FRAME)
            else:
                c.put(x, y, INSET2 if (x * 7 + y * 3) % 11 == 0 else INSET)
    x0, x1 = (0 if cl else lo), (N - 1 if cr else hi)
    y0, y1 = (0 if ct else lo), (N - 1 if cb else hi)
    # Same order as the page, so the corners match: top-right and bottom-right dark, bottom-left light.
    for x in range(x0, x1 + 1):
        if not ct:
            c.put(x, y0, BEVEL_LIGHT)
        if not cb:
            c.put(x, y1, BEVEL_DARK)
    for y in range(y0, y1 + 1):
        if not cl:
            c.put(x0, y, BEVEL_LIGHT)
        if not cr:
            c.put(x1, y, BEVEL_DARK)
    if line and not cb:
        colour = state_colour(accent, state)
        for x in range(4, 12):
            c.put(x, N - 1, colour)
    return x0 + 1, y0 + 1, x1 - 1, y1 - 1


def casing(accent=ACCENTS["storage"], state="working", line=False):
    """A plain side or back: frame and plain inset, nothing else; the accent line only with ``line`` (a plain front)."""
    c = Canvas()
    base(c, accent, state, line=line)
    return c


# ---------------------------------------------------------------- PNG in and out, stdlib only
def _chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def _raw(c):
    """The image's scanlines as PNG stores them before compression: filter byte 0, then RGBA."""
    out = bytearray()
    for y in range(c.h):
        out.append(0)
        for x in range(c.w):
            out.extend(c.get(x, y))
    return bytes(out)


def png_bytes(c):
    header = struct.pack(">IIBBBBB", c.w, c.h, 8, 6, 0, 0, 0)  # 8-bit RGBA, no interlace
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header) + _chunk(b"IDAT", zlib.compress(_raw(c), 9))
            + _chunk(b"IEND", b""))


def write_png(path, c):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png_bytes(c))


def matches(path, c):
    """True if the PNG at ``path`` is in the kit's own format (RGBA, filter 0) and holds exactly ``c``'s pixels.
    A PNG saved by an image editor fails this even with the same pixels; regenerate it with the kit."""
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return False
    pos, header, idat = 8, None, bytearray()
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            header = body
        elif kind == b"IDAT":
            idat.extend(body)
        pos += 12 + length
    if header != struct.pack(">IIBBBBB", c.w, c.h, 8, 6, 0, 0, 0):
        return False
    try:
        return zlib.decompress(bytes(idat)) == _raw(c)
    except zlib.error:
        return False


# ---------------------------------------------------------------- the casing textures this script owns
# Plain sides and backs only, so no accent line; faces with motifs belong to later MTX items. Paths are relative to
# textures/.
CASING = (
    "block/storage_controller_side",
    "block/machine/casing_side",
    "block/machine/housing",
    "block/condition_switch/side",
)


# The front line overlays of a device's network state (``LinkLook`` in Storage, owner 2026-10-17): transparent but for
# the accent line, in the state's colour. Fronts carry the online (working) line; the models draw one of these over a
# front for no channel and offline. Paths relative to textures/.
LINK_LINES = (
    ("block/link/no_channel", "error"),
    ("block/link/offline", "off"),
)


def link_line(state, accent=ACCENTS["storage"]):
    """Only the accent line of a front in ``state``, everything else transparent: an overlay over a front."""
    c = Canvas()
    colour = state_colour(accent, state)
    for x in range(4, 12):
        c.put(x, N - 1, colour)
    return c


def outputs():
    """(path relative to textures/, canvas) for every texture this script writes."""
    return [(path, casing(state="idle")) for path in CASING] + [(path, link_line(state)) for path, state in LINK_LINES]


def check(items):
    bad = []
    for path, c in items:
        f = TEXTURES / (path + ".png")
        if not f.is_file() or not matches(f, c):
            bad.append(path)
        if f.with_name(f.name + ".mcmeta").exists():
            bad.append(path + ".png.mcmeta (casing is never animated)")
    return bad


def sheet(path, sc=8):
    """Every state for every accent, one accent per row, scaled up (with the line, as on a front)."""
    gap = 4
    cell = N * sc + gap
    out = Canvas(len(STATES) * cell, len(ACCENTS) * cell, (58, 60, 66, 255))
    for row, accent in enumerate(ACCENTS.values()):
        for col, state in enumerate(STATES):
            t = casing(accent, state, line=True)
            for y in range(N * sc):
                for x in range(N * sc):
                    p = t.get(x // sc, y // sc)
                    out.put(col * cell + x, row * cell + y, p[:3], p[3])
    write_png(path, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed PNGs with the kit, write nothing")
    ap.add_argument("--sheet", help="write a contact sheet of every accent and state to this file instead")
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
            print("differs from texture_kit.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/texture_kit.py")
            return 1
        print(len(items), "casing and line textures match")
        return 0
    if args.sheet:
        sheet(args.sheet)
        return 0
    for path, c in items:
        write_png(TEXTURES / (path + ".png"), c)
    print(len(items), "casing and line textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
