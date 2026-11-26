#!/usr/bin/env python3
"""MTX item 5b: the device blocks in the family style 2a (docs/milestones/MTX.md, docs/milestones/MTX/item-05b.md).

Draws, on ``texture_kit.base``: the machines (fronts, their working overlays, tops and the lithograph's lens), the
machine observer, the world interaction blocks, the P2P links, hubs, bridges, antennas, the sensor, the condition
switch, the interface, the mail port's front, the toggle bus's collar, the logic controller, the standalone network
devices (former controller expansions) with their working overlays and the retired expansion faces.

Rules (owner, 2026-10-17): everything is static (only the controller animates), so no ``.mcmeta`` next to any of these;
fronts keep a motif in the inset and carry the accent line; sides, backs, tops and the faces of blocks without a
facing (hubs, antennas, the logic controller, the network devices and retired expansions) have none. The exceptions:
a texture that is also a cable part's front (collector, interface, condition switch) keeps the line. ``outputs`` passes
``line`` per texture. A block with an off and an on look (machines, devices) has an idle face and an overlay that holds exactly the pixels the
working look changes, drawn at full brightness by the model's ``light_emission`` element; ``overlay`` computes it as
the difference of the two faces, so the two can never drift apart. Motifs are Storage cyan; the P2P kinds, the fuel
generator's fire keep their own colours.

Sides of devices that face a block (sensor, placer, breaker, P2P links, machine observer) carry a grey arrow that
points at the texture's top; ``FacingModels`` turns each side so it points at the front.

Plain Python 3 without Pillow, like the kit. ``--check`` writes nothing and exits 1 if a committed PNG differs or one
of them has a ``.mcmeta``; ``TextureKitTest`` runs it. ``--sheet FILE`` writes a contact sheet instead.

Usage: python mtx_devices.py [--check] [--list] [--sheet FILE]
"""
import argparse
import math
import sys

from texture_kit import (ACCENTS, BEVEL_DARK, BEVEL_LIGHT, FRAME2, INSET, N, SCREEN, TEXTURES, Canvas, base,
                         hex_, matches, shade, write_png)

A = ACCENTS["storage"]
DIM = shade(A, 0.45)
MID = shade(A, 0.7)
HI = BEVEL_LIGHT
WHITE = (255, 255, 255)


def mix(a, b, f):
    return tuple(int(a[i] + (b[i] - a[i]) * f + 0.5) for i in range(3))


def window(c, x0=3, y0=3, x1=12, y1=12):
    """The dark screen a front's motif sits on."""
    c.rect(x0, y0, x1, y1, SCREEN)


def face(motif, state, *args, line):
    """A face: the 2a base (with the accent line in ``state`` if ``line``), then ``motif(c, lit, *args)``; lit unless
    idle or off."""
    c = Canvas()
    base(c, A, state=state, line=line)
    motif(c, state not in ("idle", "off"), *args)
    return c


def overlay(dark, lit):
    """The pixels of ``lit`` that differ from ``dark``, everything else transparent: the full-bright overlay that turns
    the dark face into the lit one."""
    o = Canvas()
    for y in range(N):
        for x in range(N):
            p = lit.get(x, y)
            if p != dark.get(x, y):
                o.put(x, y, p[:3])
    return o


def disc(cx, cy, r0, r1):
    """Pixels whose centre lies at a distance in [r0, r1) from (cx, cy), in the inset."""
    out = []
    for y in range(2, 14):
        for x in range(2, 14):
            d = math.hypot(x - cx, y - cy)
            if r0 <= d < r1:
                out.append((x, y))
    return out


def plot(c, pts, col):
    for x, y in pts:
        c.put(x, y, col)


def mirror(pts):
    """Left-half pixels and their mirror image across the vertical centre line."""
    return list(pts) + [(N - 1 - x, y) for x, y in pts]


# ---------------------------------------------------------------- sides and tops
def arrow_side(c, lit):
    """A facing device's side: a grey arrow pointing at the texture's top."""
    for i, y in enumerate((4, 5, 6)):
        c.rect(7 - i, y, 8 + i, y, HI)
    c.rect(7, 7, 8, 11, HI)


def casing_top(c, lit):
    """The machines' top and bottom: a recessed plate the raised parts stand on."""
    c.rect(4, 4, 11, 11, FRAME2)
    c.rect(4, 4, 11, 4, BEVEL_DARK)
    c.rect(4, 4, 4, 11, BEVEL_DARK)
    c.rect(5, 11, 11, 11, HI)
    c.rect(11, 5, 11, 11, HI)


def fuel_top(c, lit):
    """The fuel generator's top: three vent slits with a lit lip below each."""
    for y in (4, 7, 10):
        c.rect(4, y, 11, y, SCREEN)
        c.rect(4, y + 1, 11, y + 1, HI)


def lens(c, lit):
    """The lithograph's raised lens (its model shows pixels 3 to 12): a glass disc in a grey ring."""
    plot(c, disc(7.5, 7.5, 3.5, 4.6), HI)
    plot(c, disc(7.5, 7.5, 0, 3.5), SCREEN)
    plot(c, disc(7.5, 7.5, 0, 1.5), DIM)
    c.put(6, 6, mix(SCREEN, WHITE, 0.35))


# ---------------------------------------------------------------- machine fronts
def assembler(c, lit):
    """A chip: a grey die with pins, its core lit while working."""
    window(c)
    c.rect(6, 6, 9, 9, HI)
    c.rect(7, 7, 8, 8, A if lit else DIM)
    for p in (6, 9):
        for x, y in ((p, 4), (p, 5), (p, 10), (p, 11), (4, p), (5, p), (10, p), (11, p)):
            c.put(x, y, MID if lit else FRAME2)


def fabricator(c, lit):
    """A press: the ram above, the anvil below and the part between them, which glows while pressed."""
    window(c)
    c.rect(7, 3, 8, 4, HI)
    c.rect(4, 5, 11, 6, HI)
    c.rect(4, 11, 11, 12, HI)
    c.rect(6, 9, 9, 10, A if lit else DIM)
    if lit:
        c.rect(5, 7, 10, 7, MID)


FIRE_DARK = hex_("#3a2420")
FIRE_LO = hex_("#7a2a10")
FIRE_HI = hex_("#ffb040")


def fuel_generator(c, lit):
    """A bolt above a fire grate; the fire burns while it works."""
    window(c)
    bolt = [(9, 4), (8, 5), (7, 6), (8, 6), (9, 6), (10, 6), (9, 7), (8, 8), (7, 9)]
    plot(c, bolt, A if lit else DIM)
    for y in (10, 11, 12):
        for x in range(4, 12):
            if lit:
                c.put(x, y, mix(FIRE_LO, FIRE_HI, (12 - y) / 2 * 0.6 + ((x * 5 + y * 3) % 4) * 0.1))
            else:
                c.put(x, y, FIRE_DARK)
    for x in (5, 8, 10):
        c.put(x, 12, FRAME2)


def lithograph(c, lit):
    """A lens ring over a wafer; the lens core and the wafer light while it etches."""
    window(c)
    plot(c, disc(7.5, 6.5, 3.0, 4.0), HI)
    plot(c, disc(7.5, 6.5, 0, 1.6), A if lit else DIM)
    c.rect(4, 11, 11, 11, MID if lit else FRAME2)
    if lit:
        c.rect(7, 9, 8, 10, MID)


# ---------------------------------------------------------------- facing devices and world blocks
def observer(c, lit):
    """An eye with a cyan iris."""
    window(c)
    outline = mirror([(6, 5), (7, 5), (4, 6), (5, 6), (3, 7), (3, 8), (4, 9), (5, 9), (6, 10), (7, 10)])
    plot(c, outline, HI)
    c.rect(6, 6, 9, 9, DIM)
    c.rect(7, 7, 8, 8, A)


def breaker(c, lit):
    """Crusher jaws (owner 2026-10-17): grey jaw edges at the top and bottom of the window, cyan teeth biting towards
    the middle, a dim cyan block caught between them. Mirror-symmetric."""
    window(c)
    c.rect(3, 3, 12, 3, HI)
    c.rect(3, 12, 12, 12, HI)
    for x in (3, 4, 7, 8, 11, 12):
        c.put(x, 4, A)
        c.put(x, 11, A)
    for x in (5, 6, 9, 10):
        c.put(x, 5, A)
        c.put(x, 10, A)
    c.rect(6, 7, 9, 8, shade(A, 0.55))


def placer(c, lit):
    """A block outline in cyan between grey placing marks."""
    window(c)
    c.rect(5, 5, 10, 10, A)
    c.rect(6, 6, 9, 9, DIM)
    for x, y in ((3, 3), (4, 3), (3, 4), (12, 3), (11, 3), (12, 4), (3, 12), (4, 12), (3, 11), (12, 12), (11, 12),
                 (12, 11)):
        c.put(x, y, HI)


def collector(c, lit):
    """Vacuum rings: a dark outer ring and a dim cyan ring around a bright core, the suction that pulls items in."""
    window(c)
    for y in range(3, 13):
        for x in range(3, 13):
            d = math.hypot(x - 7.5, y - 7.5)
            if 4.2 <= d < 5:
                c.put(x, y, BEVEL_DARK)
            elif 2.6 <= d < 3.3:
                c.put(x, y, shade(A, 0.55))
            elif d < 1.5:
                c.put(x, y, mix(A, WHITE, 0.45))


def sensor(c, lit):
    """A lens: grey rim, cyan ring, dark pupil."""
    window(c)
    plot(c, disc(7.5, 7.5, 3.6, 4.6), HI)
    plot(c, disc(7.5, 7.5, 2.2, 3.6), A)
    c.rect(7, 7, 8, 8, DIM)


P2P_COLOURS = {"energy": hex_("#f0d040"), "fluid": hex_("#3a8ab8"), "item": A, "redstone": hex_("#e05a5a")}


def p2p(c, lit, kind):
    """Two linked ends in the kind's colour."""
    col = P2P_COLOURS[kind]
    window(c)
    c.rect(4, 4, 6, 6, col)
    c.rect(9, 9, 11, 11, col)
    plot(c, [(7, 7), (8, 8)], shade(col, 0.6))


def tier_pips(c, tier):
    """Tier marks in the inset's corners: none for tier 1, the top two for tier 2, all four for tier 3."""
    corners = {1: (), 2: ((3, 3), (12, 3)), 3: ((3, 3), (12, 3), (3, 12), (12, 12))}[tier]
    plot(c, corners, A)


def hub(c, lit, tier):
    """A ring with a cyan core and four cable arms."""
    window(c)
    for a in range(3, 5):
        c.rect(7, a - 1, 8, a - 1, DIM)
        c.rect(7, 16 - a, 8, 16 - a, DIM)
        c.rect(a - 1, 7, a - 1, 8, DIM)
        c.rect(16 - a, 7, 16 - a, 8, DIM)
    plot(c, disc(7.5, 7.5, 3.5, 4.5), HI)
    plot(c, disc(7.5, 7.5, 0, 1.8), A)
    tier_pips(c, tier)


def bridge(c, lit, tier):
    """Two strands twisted round each other, the front one bright."""
    window(c)
    for y in range(3, 13):
        a = y * 0.5
        x1 = round(7.5 + math.sin(a) * 3.5)
        x2 = N - 1 - x1  # the mirror image, so the twist is symmetric about the face's centre
        front = math.cos(a) > 0
        c.put(x1, y, A if front else DIM)
        c.put(x2, y, DIM if front else A)
    tier_pips(c, tier)


def antenna(c, lit, tier):
    """A mast on a foot with one signal arc above it per tier."""
    window(c)
    c.rect(7, 10, 8, 11, HI)
    c.rect(5, 12, 10, 12, HI)
    c.rect(7, 9, 8, 9, A)
    for i in range(tier):
        y = 7 - 2 * i
        col = (A, MID, DIM)[i]
        c.rect(6 - i, y, 9 + i, y, col)
        c.put(5 - i, y + 1, col)
        c.put(10 + i, y + 1, col)


def condition_switch(c, lit):
    """A lever in a slot: down and grey when off, up with a cyan slot when on."""
    c.rect(6, 3, 9, 12, SCREEN)
    if lit:
        c.rect(7, 8, 8, 11, MID)
        c.rect(5, 4, 10, 6, HI)
        c.rect(7, 5, 8, 5, A)
    else:
        c.rect(5, 9, 10, 11, HI)
        c.rect(7, 10, 8, 10, FRAME2)


def lever_face(on):
    """The condition switch's front: the online accent line, the lever down (off) or up (on)."""
    c = Canvas()
    base(c, A, state="working", line=True)
    condition_switch(c, on)
    return c


def interface(c, lit):
    """Nine slots, the middle one cyan."""
    window(c)
    for gy in (4, 7, 10):
        for gx in (4, 7, 10):
            c.rect(gx, gy, gx + 1, gy + 1, A if (gx, gy) == (7, 7) else HI)


def mail_port(c, lit):
    """An envelope with a cyan flap."""
    window(c)
    c.rect(4, 5, 11, 10, HI)
    plot(c, mirror([(4, 5), (5, 6), (6, 7), (7, 8)]), A)


def logic_controller(c, lit):
    """Three nodes wired together."""
    window(c)
    c.rect(6, 5, 8, 5, DIM)
    c.rect(8, 5, 8, 9, DIM)
    c.rect(4, 11, 9, 11, DIM)
    c.rect(9, 9, 9, 11, DIM)
    c.rect(4, 4, 5, 5, A)
    c.rect(9, 8, 11, 10, A)
    c.rect(3, 10, 4, 11, MID)


def subnet_controller(c, lit):
    """The column's ends: a parent node whose link splits into three child nodes, the network it runs."""
    window(c)
    c.rect(7, 6, 8, 7, DIM)
    c.rect(4, 8, 11, 8, DIM)
    for x in (4, 11):
        c.rect(x, 9, x, 9, DIM)
    c.rect(7, 9, 8, 9, DIM)
    c.rect(6, 3, 9, 5, A)
    for x0 in (3, 10):
        c.rect(x0, 10, x0 + 2, 12, MID)
    c.rect(7, 10, 8, 12, MID)


# ---------------------------------------------------------------- network devices, retired expansions, status lights
def security_module(c, lit):
    """A padlock: grey shackle, body cyan while active."""
    window(c)
    plot(c, mirror([(6, 3), (7, 3), (5, 4), (5, 5), (5, 6)]), HI)
    c.rect(4, 7, 11, 12, A if lit else DIM)
    c.rect(7, 9, 8, 10, SCREEN)


def wireless_access_mount(c, lit):
    """A signal source with two arcs."""
    window(c)
    col = A if lit else DIM
    c.rect(7, 10, 8, 11, col)
    c.rect(5, 12, 10, 12, HI)
    for r in (3.0, 5.0):
        for x, y in disc(7.5, 10.5, r - 0.5, r + 0.5):
            if y <= 9 and y >= 3:
                c.put(x, y, col)


def antenna_mount(c, lit):
    """A dish on a mast."""
    window(c)
    col = A if lit else DIM
    plot(c, mirror([(4, 4), (5, 5), (6, 6), (7, 7)]), col)
    c.rect(7, 8, 8, 11, HI)
    c.rect(5, 12, 10, 12, HI)
    c.rect(7, 4, 8, 4, col)


def dimension_bridge(c, lit):
    """A portal frame with a swirl inside."""
    c.rect(4, 3, 11, 12, HI)
    c.rect(5, 4, 10, 12, SCREEN)
    for y in range(4, 13):
        for x in range(5, 11):
            if (x + 2 * y) % 5 == 0:
                c.put(x, y, A if lit else DIM)


def network_anchor(c, lit):
    """An anchor."""
    window(c)
    col = A if lit else DIM
    c.rect(7, 3, 8, 3, HI)
    c.rect(6, 4, 9, 4, HI)
    c.rect(7, 5, 8, 11, col)
    c.rect(5, 6, 10, 6, col)
    plot(c, mirror([(4, 9), (4, 10), (5, 11), (6, 12), (7, 12)]), col)


def toggle_collar(linked, inverted):
    """The toggle bus's collar (B09 item 19), one sprite for all its faces. The cable model maps it one texel per block
    pixel, which is p or 15 - p on either axis, so the pattern is symmetric under both: it depends only on how far a
    pixel is from the nearest edge. Along the cable the collar covers 0 to 3 (or 12 to 15), across it 5 to 10: the
    sides show the edge, the band at 1 and 2 (cyan while linked, dark while open) and, on the inverted bus, a light ring
    at 3; the end faces show only casing."""
    c = Canvas()
    band = A if linked else SCREEN
    for y in range(N):
        for x in range(N):
            d = min(x, N - 1 - x, y, N - 1 - y)
            if d == 0:
                col = BEVEL_DARK
            elif d <= 2:
                col = band
            elif d == 3 and inverted:
                col = HI
            else:
                col = INSET
            c.put(x, y, col)
    return c


def retired(c, lit):
    """A retired expansion (old worlds only, never lit): an empty module slot."""
    window(c, 4, 4, 11, 11)
    for y in (6, 9):
        c.rect(5, y, 10, y, FRAME2)


DEVICES = {
    "network_anchor": network_anchor,
    "security_module": security_module,
    "antenna_mount": antenna_mount,
    "wireless_access_mount": wireless_access_mount,
    "dimension_bridge": dimension_bridge,
}
RETIRED = ("bandwidth_array", "batch_amplifier", "device_bus", "efficiency_core", "surge_regulator",
           "telemetry_recorder", "alarm_module", "ledger_module")
MACHINES = {"assembler": assembler, "fabricator": fabricator, "fuel_generator": fuel_generator,
            "lithograph": lithograph}


FRONT, NO_LINE = True, False


def outputs():
    """(path relative to textures/, canvas) for every texture this script writes, with the accent line on fronts only
    (``FRONT``), decided by the model face each texture is drawn on."""
    o = []
    for name, motif in MACHINES.items():
        idle = face(motif, "idle", line=FRONT)
        o.append(("block/machine/" + name + "_front", idle))
        o.append(("block/machine/" + name + "_front_active", overlay(idle, face(motif, "working", line=FRONT))))
    o.append(("block/machine/casing_top", face(casing_top, "idle", line=NO_LINE)))
    o.append(("block/machine/fuel_generator_top", face(fuel_top, "idle", line=NO_LINE)))
    o.append(("block/machine/lithograph_lens", face(lens, "idle", line=NO_LINE)))
    for side in ("block/machine_observer_side", "block/world/side", "block/p2p/side", "block/sensor/side"):
        o.append((side, face(arrow_side, "idle", line=NO_LINE)))
    o.append(("block/machine_observer_front", face(observer, "working", line=FRONT)))
    o.append(("block/world/breaker_front", face(breaker, "working", line=FRONT)))
    o.append(("block/world/placer_front", face(placer, "working", line=FRONT)))
    # The collector block shows this on every face, its cable part as the front: kept as a front.
    o.append(("block/world/collector", face(collector, "working", line=FRONT)))
    o.append(("block/sensor/front", face(sensor, "working", line=FRONT)))
    for kind in P2P_COLOURS:
        o.append(("block/p2p/" + kind, face(p2p, "working", kind, line=FRONT)))
    for tier in (1, 2, 3):
        o.append((f"block/hub/tier_{tier}", face(hub, "working", tier, line=NO_LINE)))  # cube_all
        o.append((f"block/bridge/face_tier_{tier}", face(bridge, "working", tier, line=FRONT)))  # facing ends
        o.append((f"block/antenna/tier_{tier}", face(antenna, "working", tier, line=NO_LINE)))  # cube_all
    # The switch block shows these on every face, its cable part as the front: kept as fronts.
    # Both with the online line, which the network's state recolours in the world (LinkLook); the lever shows on/off.
    o.append(("block/condition_switch/front_off", lever_face(False)))
    o.append(("block/condition_switch/front_on", lever_face(True)))
    # The interface block is cube_all, its cable part's front is this: kept as a front.
    o.append(("block/interface/face", face(interface, "working", line=FRONT)))
    o.append(("block/mail_port_front", face(mail_port, "working", line=FRONT)))
    # The toggle bus's collar: no casing base or line, a cable part with no front; the band shows its state.
    for prefix, inverted in (("", False), ("inverted_", True)):
        o.append(("block/toggle_bus/" + prefix + "open", toggle_collar(False, inverted)))
        o.append(("block/toggle_bus/" + prefix + "linked", toggle_collar(True, inverted)))
    o.append(("block/logic_controller", face(logic_controller, "working", line=NO_LINE)))  # cube_all
    o.append(("block/subnet_controller_end", face(subnet_controller, "working", line=FRONT)))  # facing ends
    for kind, motif in DEVICES.items():
        # Network devices show their face on all four sides and have no facing.
        idle = face(motif, "idle", line=NO_LINE)
        o.append(("block/controller/" + kind, idle))
        o.append(("block/controller/" + kind + "_glow_1", overlay(idle, face(motif, "working", line=NO_LINE))))
    for kind in RETIRED:
        o.append(("block/controller/" + kind, face(retired, "off", line=NO_LINE)))
    return o


def check(items):
    bad = []
    for path, c in items:
        f = TEXTURES / (path + ".png")
        if not f.is_file() or not matches(f, c):
            bad.append(path)
        if f.with_name(f.name + ".mcmeta").exists():
            bad.append(path + ".png.mcmeta (only the controller animates)")
    return bad


def sheet(path, items, sc=8, cols=10):
    gap = 4
    cell = N * sc + gap
    rows = (len(items) + cols - 1) // cols
    out = Canvas(cols * cell, rows * cell, (90, 110, 80, 255))
    for i, (_, t) in enumerate(items):
        ox, oy = (i % cols) * cell, (i // cols) * cell
        for y in range(N * sc):
            for x in range(N * sc):
                p = t.get(x // sc, y // sc)
                if p[3]:
                    out.put(ox + x, oy + y, p[:3])
    write_png(path, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare the committed PNGs with this script, write nothing")
    ap.add_argument("--sheet", help="write a contact sheet to this file instead")
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
            print("differs from mtx_devices.py:", path)
        if bad:
            print("regenerate with: python storage/neoforge/src/textures/mtx_devices.py")
            return 1
        print(len(items), "device textures match")
        return 0
    if args.sheet:
        sheet(args.sheet, items)
        return 0
    for path, c in items:
        write_png(TEXTURES / (path + ".png"), c)
    print(len(items), "device textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
