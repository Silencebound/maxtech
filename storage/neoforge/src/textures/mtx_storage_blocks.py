#!/usr/bin/env python3
"""Storage's storage blocks in the MaxTech family style 2a (MTX item 5a, docs/milestones/MTX/item-05a.md).

Drives, the bulk drive, the storage blocks, the IO port, energy cells, the storage, network and workbench blocks, the crafting CPU parts, the crafting
assembler and the crafter with their glass windows, the pattern and generator providers and the creative energy source. Every face starts from
``texture_kit.base`` with the Storage accent: fronts carry their motif in the inset and the bright ``working`` line;
sides, tops, backs and the faces of blocks without a facing (CPU parts, energy cells, the crafting assembler, the
creative source) have no accent line (owner, 2026-10-17: the line marks a front only). ``outputs`` passes ``line`` per
texture from the model face it is drawn on. Nothing here is animated, so none of these textures may have a ``.mcmeta``.

The crafting CPU blocks have connected textures (B09 item 28): besides the lone block (``block/cpu/<name>``, every
edge framed) each has ``block/cpu/<name>_<mask>`` for the other 15 edge masks of ``ControllerFace`` (bit set = edge
framed: 1 top, 2 right, 4 bottom, 8 left), drawn with the kit's ``conn`` flags; ``CpuClusterModel`` picks them.

The drive's slot bays and its cell sprites match ``DriveBays`` (columns 2 and 9, rows 3, 6, 9 and 12, cells 5 x 2
pixels). A cell sprite is 32 x 32 and transparent but for one cell at (13, 15), its light at the right end; the drive
model draws it flush over the front through a 16 x 16 window of the sprite, mirrored for the right column so the light
faces the divider. Cell lights use the state colours: below 75 % the accent, from 75 % amber, full red, inactive
off-grey.

Plain Python 3 like the kit (no Pillow). ``--check`` writes nothing and exits 1 if a committed PNG differs or has a
``.mcmeta``; ``TextureKitTest`` runs it.

Usage: python mtx_storage_blocks.py [--check] [--list]
"""
import argparse
import sys

sys.dont_write_bytecode = True  # importing the kit must not leave a __pycache__ in the source tree
from texture_kit import (ACCENTS, BEVEL_DARK, BEVEL_LIGHT, INSET, INSET2, SCREEN, TEXTURES, Canvas, base,
                         check, hex_, shade, state_colour, write_png)

A = ACCENTS["storage"]
DIM = shade(A, 0.45)
MID = shade(A, 0.6)
HI = BEVEL_LIGHT
LO = BEVEL_DARK
WHITE = (255, 255, 255)


def mix(a, b, f):
    return tuple(int(a[i] + (b[i] - a[i]) * f + 0.5) for i in range(3))


FRONT, NO_LINE = True, False


def face(line, state="working", conn=(False, False, False, False)):
    c = Canvas()
    base(c, A, state, conn, line=line)
    return c


def conn_of(mask):
    """The kit's ``conn`` flags for a connected-texture edge mask (bit set = edge framed: 1 top, 2 right, 4 bottom,
    8 left; ``ControllerFace``): an unframed edge continues into the neighbour."""
    return tuple((mask >> i) & 1 == 0 for i in range(4))


def plain():
    """Sides, tops and backs: the plain base, no accent line."""
    return face(False)


def window(c, x0, y0, x1, y1):
    """A dark recess in the inset, in the screen colour."""
    c.rect(x0, y0, x1, y1, SCREEN)


def pips(c, y, lit, colour=A, unlit=SCREEN):
    """Three 2 px tier pips centred in row y, the first ``lit`` in the accent, the rest dark."""
    for i, x in enumerate((4, 7, 10)):
        col = colour if i < lit else unlit
        c.put(x, y, col)
        c.put(x + 1, y, col)


# ---------------------------------------------------------------- drives
DRIVE_COLUMNS = (2, 9)
DRIVE_ROWS = (3, 6, 9, 12)
CELL_W, CELL_H = 5, 2
CELL_SPRITE = 32
CELL_AT = (13, 15)
CELL_TOP = hex_("#a3acb6")
CELL_BODY = hex_("#737c87")


def drive_front(line):
    """Eight empty slot bays, two columns of four; the drive model puts a cell in each occupied one."""
    c = face(line)
    for y in DRIVE_ROWS:
        for x in DRIVE_COLUMNS:
            c.rect(x, y, x + CELL_W - 1, y + CELL_H - 1, SCREEN)
    for y in range(2, 14):
        c.put(7, y, LO)
        c.put(8, y, HI)
    return c


def drive_cell(state):
    """A cell flush in its bay, alone on a transparent 32 x 32 sprite (see ``DriveBays``): a light steel body with a
    lighter top row and the state light, both rows tall, at the right end. The body is much lighter than the casing so a
    filled bay reads against the near-black empty ones from a distance (owner 2026-10-18)."""
    c = Canvas(CELL_SPRITE, CELL_SPRITE)
    x, y = CELL_AT
    c.rect(x, y, x + CELL_W - 1, y + CELL_H - 1, CELL_BODY)
    c.rect(x, y, x + CELL_W - 2, y, CELL_TOP)
    c.rect(x + CELL_W - 1, y, x + CELL_W - 1, y + CELL_H - 1, state_colour(A, state))
    return c


def bulk_drive_front(line):
    """One wide bay holding a single bulk cell with its light."""
    c = face(line)
    window(c, 2, 3, 13, 12)
    c.rect(3, 6, 12, 6, HI)
    c.rect(3, 7, 12, 9, INSET)
    c.rect(3, 9, 12, 9, INSET2)
    c.rect(10, 7, 11, 8, A)
    for x in (4, 6, 8):
        c.put(x, 8, LO)
    return c


def storage_block_front(tier, line):
    """Six small boxes in one bay, many kinds in one block (B09 item 27), and the tier's pips below."""
    c = face(line)
    window(c, 3, 2, 12, 8)
    for y in (3, 6):
        for x in (4, 7, 10):
            c.rect(x, y, x + 1, y + 1, CELL_BODY)
            c.rect(x, y, x + 1, y, CELL_TOP)
    pips(c, 11, tier)
    return c


def io_port_front(line):
    """Three cells in a bay on each side and an arrow between them: cells go in on the left and come out on the right
    (B09 item 11). Static; the cells are part of the texture."""
    c = face(line)
    for x0 in (2, 10):
        window(c, x0, 2, x0 + 3, 12)
        for y in (3, 6, 9):
            c.rect(x0, y, x0 + 2, y + 1, CELL_BODY)
            c.rect(x0, y, x0 + 2, y, CELL_TOP)
            c.rect(x0 + 3, y, x0 + 3, y + 1, DIM)
    c.rect(6, 7, 9, 7, A)
    for x, y in ((8, 6), (8, 8), (7, 5), (7, 9)):
        c.put(x, y, A)
    return c


# ---------------------------------------------------------------- energy cells
def energy_side(charge):
    """A level window with a static bar: charge 0 to 4 fills 0, 3, 5, 8 or 10 of its 10 rows."""
    c = plain()
    window(c, 4, 2, 11, 13)
    level = (charge * 10 + 2) // 4
    for k in range(level):
        y = 12 - k
        col = mix(MID, A, k / 9)
        c.rect(5, y, 10, y, col)
    for y in (5, 8, 10):
        c.put(4, y, LO)
    return c


def energy_end(tier):
    """The terminal on each end and three tier pips."""
    c = plain()
    window(c, 5, 6, 10, 11)
    c.rect(6, 7, 9, 10, LO)
    c.rect(7, 8, 8, 9, MID)
    pips(c, 3, tier)
    return c


def creative_source(line):
    """An energy cell that never runs dry: a full bolt on a dark window."""
    c = face(line)
    window(c, 3, 2, 12, 12)
    bolt = ((9, 3), (8, 4), (9, 4), (7, 5), (8, 5), (6, 6), (7, 6), (6, 7), (7, 7), (8, 7), (9, 7), (8, 8),
            (9, 8), (7, 9), (8, 9), (7, 10), (6, 11))
    for x, y in bolt:
        c.put(x, y, mix(A, WHITE, 0.45))
    return c


# ---------------------------------------------------------------- chests and workbench
def chest_front(network, line):
    """A lid seam with a latch; the network chest adds three linked nodes on the lid, the middle one 2 px."""
    c = face(line)
    c.rect(2, 7, 13, 7, SCREEN)
    c.rect(2, 8, 13, 8, HI)
    c.rect(7, 6, 8, 9, LO)
    c.rect(7, 7, 8, 8, A)
    if network:
        window(c, 4, 3, 11, 5)
        c.rect(5, 4, 10, 4, DIM)
        for x in (5, 7, 8, 10):
            c.put(x, 4, A)
    return c


def workbench_front(line):
    """A cell held in a bay above the bench surface."""
    c = face(line)
    window(c, 4, 2, 11, 9)
    c.rect(6, 3, 9, 8, INSET)
    c.rect(6, 3, 9, 3, HI)
    c.rect(7, 5, 8, 5, A)
    c.rect(2, 11, 13, 11, HI)
    c.rect(2, 12, 13, 12, LO)
    return c


# ---------------------------------------------------------------- crafting
def cpu_core(tier, line, mask=15):
    """A chip with pins on every side (where the item chips have theirs) and the tier as pips under the die."""
    c = face(line, conn=conn_of(mask))
    window(c, 4, 4, 11, 11)
    for i in (4, 6, 9, 11):
        for x, y in ((i, 3), (i, 12), (3, i), (12, i)):
            c.put(x, y, HI)
    c.rect(6, 5, 9, 8, MID)
    c.rect(7, 6, 8, 7, A)
    pips(c, 10, tier, unlit=LO)
    return c


def cpu_cache(line, mask=15):
    """A grid of memory cells, every other one lit."""
    c = face(line, conn=conn_of(mask))
    window(c, 3, 3, 12, 12)
    for y in (4, 7, 10):
        for x in (4, 7, 10):
            c.rect(x, y, x + 1, y + 1, A if (x + y) % 2 == 0 else LO)
    return c


def cpu_storage(line, mask=15):
    """Stacked memory strips, each with a light at its right end."""
    c = face(line, conn=conn_of(mask))
    for y in (3, 6, 9, 12):
        c.rect(3, y, 12, y + 1, SCREEN)
        c.rect(4, y, 9, y, LO)
        c.put(11, y, A)
    return c


def cpu_lane(line, mask=15):
    """Three parallel lanes with lit tips, staggered so they read as separate lanes."""
    c = face(line, conn=conn_of(mask))
    window(c, 2, 3, 13, 12)
    for y, tip in ((5, 11), (8, 9), (11, 11)):
        c.rect(3, y, tip - 1, y, DIM)
        c.rect(tip, y, tip + 1, y, A)
    return c


def crafting_assembler(line):
    """A 3 x 3 crafting grid with the result lit in the middle."""
    c = face(line)
    window(c, 3, 3, 12, 12)
    for y in (4, 7, 10):
        for x in (4, 7, 10):
            c.rect(x, y, x + 1, y + 1, HI)
    c.rect(7, 7, 8, 8, A)
    return c


GLASS_EDGE = shade(A, 0.3)


def glass_window(line):
    """A glass window (B09 item 29): a dark rim round a see-through pane (fully transparent pixels, so the face stays
    in the cutout layer; the model's inner walls show behind it, and the item being crafted turns in the middle), with
    a light glint in the corners and a dim cyan edge along the rim's top."""
    c = face(line)
    c.rect(2, 2, 13, 13, SCREEN)
    c.rect(3, 3, 12, 12, (0, 0, 0), 0)
    for x in range(4, 12):
        c.put(x, 2, GLASS_EDGE)
    # Opaque glint pixels: any partly transparent pixel would put the whole face in the translucent layer.
    c.put(3, 3, HI)
    c.put(4, 3, HI)
    c.put(3, 4, HI)
    c.put(12, 11, HI)
    return c


def window_inner():
    """The inside walls behind the glass: dark, with a faint 4 px grid so the depth reads."""
    c = Canvas()
    c.rect(0, 0, 15, 15, SCREEN)
    for i in range(0, 16, 4):
        for j in range(16):
            c.put(i, j, mix(SCREEN, INSET, 0.35))
            c.put(j, i, mix(SCREEN, INSET, 0.35))
    return c


def pattern_provider_face(line):
    """A pattern sheet with lines on a dark window."""
    c = face(line)
    window(c, 3, 2, 12, 12)
    c.rect(5, 4, 10, 11, INSET)
    c.rect(5, 4, 10, 4, HI)
    for y in (6, 8, 10):
        c.rect(6, y, 9 if y != 8 else 8, y, DIM)
    c.put(6, 6, A)
    return c


def facing_side():
    """A side of a device that faces a block: plain base with a dim arrow towards the texture's top (the model turns
    the face so it points at the front)."""
    c = plain()
    for i in range(3):
        c.put(7 - i, 5 + i, DIM)
        c.put(8 + i, 5 + i, DIM)
    c.rect(7, 5, 8, 10, DIM)
    return c


def generator_provider_face(line):
    """A redstone-style square wave: the signal the provider sends to the machine in front."""
    c = face(line)
    window(c, 2, 4, 13, 11)
    wave = [(2, 9), (3, 9), (4, 9), (4, 8), (4, 7), (4, 6), (5, 6), (6, 6), (7, 6), (7, 7), (7, 8), (7, 9), (8, 9),
            (9, 9), (10, 9), (10, 8), (10, 7), (10, 6), (11, 6), (12, 6), (13, 6)]
    for x, y in wave:
        c.put(x, y, A)
    return c


# ---------------------------------------------------------------- outputs
def outputs():
    """(path relative to textures/, canvas) for every texture this script writes. ``FRONT`` and ``NO_LINE`` say per
    texture whether the model draws it as a front (accent line) or not."""
    o = [
        ("block/drive/front", drive_front(FRONT)),
        ("block/drive/cell_low", drive_cell("working")),
        ("block/drive/cell_high", drive_cell("warning")),
        ("block/drive/cell_full", drive_cell("error")),
        ("block/drive/cell_inactive", drive_cell("off")),
        ("block/bulk_drive_front", bulk_drive_front(FRONT)),
        ("block/io_port_front", io_port_front(FRONT)),
        ("block/storage_block/side", plain()),
        ("block/creative_energy_source", creative_source(NO_LINE)),  # cube_all, no facing
        ("block/storage_chest_front", chest_front(False, FRONT)),
        ("block/storage_chest_side", plain()),
        ("block/storage_chest_top", plain()),
        ("block/network_chest_front", chest_front(True, FRONT)),  # also the network chest part's front
        ("block/network_chest_side", plain()),
        ("block/network_chest_top", plain()),
        ("block/cell_workbench_front", workbench_front(FRONT)),
        ("block/cell_workbench_side", plain()),
        ("block/cell_workbench_top", plain()),
        ("block/cpu/cache", cpu_cache(NO_LINE)),  # the CPU parts are cube_all
        ("block/cpu/lane", cpu_lane(NO_LINE)),
        ("block/cpu/storage", cpu_storage(NO_LINE)),
        ("block/crafting_assembler", crafting_assembler(NO_LINE)),  # top and bottom; also the crafter's top
        ("block/crafting_assembler_window", glass_window(NO_LINE)),  # its four sides, no facing
        ("block/crafter_front", glass_window(FRONT)),
        ("block/crafting_window_inner", window_inner()),
        ("block/pattern_provider_face", pattern_provider_face(FRONT)),
        ("block/pattern_provider_side", facing_side()),
        ("block/generator_provider_face", generator_provider_face(FRONT)),
    ]
    for charge in range(5):
        o.append((f"block/energy_cell/side_{charge}", energy_side(charge)))
    for tier in (1, 2, 3):
        o.append((f"block/storage_block/front_tier_{tier}", storage_block_front(tier, FRONT)))
        o.append((f"block/energy_cell/end_tier_{tier}", energy_end(tier)))
        o.append((f"block/cpu/core_tier_{tier}", cpu_core(tier, NO_LINE)))
    # Connected textures (B09 item 28): the lone block above is mask 15; the other masks drop the frame on the edges
    # that continue into the same cluster. The symbol stays; no accent line (no front).
    for mask in range(15):
        o.append((f"block/cpu/cache_{mask}", cpu_cache(NO_LINE, mask)))
        o.append((f"block/cpu/lane_{mask}", cpu_lane(NO_LINE, mask)))
        o.append((f"block/cpu/storage_{mask}", cpu_storage(NO_LINE, mask)))
        for tier in (1, 2, 3):
            o.append((f"block/cpu/core_tier_{tier}_{mask}", cpu_core(tier, NO_LINE, mask)))
    return o


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed PNGs with this script, write nothing")
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
            print("differs from mtx_storage_blocks.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_storage_blocks.py")
            return 1
        print(len(items), "storage block textures match")
        return 0
    for path, c in items:
        write_png(TEXTURES / (path + ".png"), c)
    print(len(items), "storage block textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
