"""Draws the tube-fx prototype textures (proto/tube-fx branch only, never merged).

Run from anywhere: python3 make_tube_fx_textures.py. Writes PNGs and .mcmeta files to the gametest resources.
"""
import json
import math
import os
import random

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "resources", "assets", "maxtech_lib", "textures", "block",
                   "proto_fx")
S = 16


def save(name, frames, frametime=None, interpolate=False):
    img = Image.new("RGBA", (S, S * len(frames)))
    for i, f in enumerate(frames):
        img.paste(f, (0, i * S))
    img.save(os.path.join(OUT, name + ".png"))
    meta = os.path.join(OUT, name + ".png.mcmeta")
    if len(frames) > 1:
        anim = {"frametime": frametime}
        if interpolate:
            anim["interpolate"] = True
        with open(meta, "w") as fh:
            json.dump({"animation": anim}, fh, indent=2)
            fh.write("\n")
    elif os.path.exists(meta):
        os.remove(meta)


def from_rows(rows, palette, width=S):
    img = Image.new("RGBA", (width, len(rows)))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            img.putpixel((x, y), palette[ch])
    return img


def blank():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def put(img, x, y, rgba):
    """Alpha-over one pixel on a 16x16 torus."""
    x %= S
    y %= S
    r, g, b, a = img.getpixel((x, y))
    nr, ng, nb, na = rgba
    if na >= a:
        img.putpixel((x, y), rgba)


GLASS = [
    "................",
    "..hhhHHhhh..hhs.",
    "..sss..ssss...s.",
    "........,.......",
    "..,.......,.....",
    "......b.....,...",
    ".,......,.......",
    "...........,....",
    "....,...........",
    "..........d.....",
    ".,...........,..",
    "......,.........",
    "...ss.......sss.",
    "........,.......",
    ".d...........,..",
    "................",
]
GLASS_PAL = {".": (120, 206, 224, 52), ",": (100, 190, 214, 70), "h": (200, 250, 255, 150),
             "H": (235, 255, 255, 205), "s": (160, 232, 244, 100), "d": (110, 150, 150, 80),
             "b": (190, 246, 252, 130)}
RAIL = ["BAhBCBAABBrCBABB", "CBBCCBBCBCBCCBCB", "DDEDDCDDEDDDEDCD", "EEFEEEDEFEEEFEEE"]
RAIL_PAL = {k: v + (255,) for k, v in {"h": (188, 196, 202), "r": (206, 212, 218), "A": (150, 158, 166),
                                       "B": (124, 132, 140), "C": (100, 107, 114), "D": (76, 82, 89),
                                       "E": (56, 61, 67), "F": (44, 48, 53)}.items()}


def bubbles():
    rnd = random.Random(11)
    spots = [(rnd.randrange(S), rnd.randrange(S), rnd.choice([0, 0, 1, 1, 2])) for _ in range(7)]
    frames = []
    for k in range(S):
        f = blank()
        for x, y, size in spots:
            y += k
            if size == 0:
                put(f, x, y, (240, 252, 255, 150))
            elif size == 1:
                for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                    put(f, x + dx, y + dy, (200, 238, 250, 120))
                put(f, x, y, (255, 255, 255, 210))
            else:
                for dx, dy in ((1, 0), (2, 0), (0, 1), (3, 1), (0, 2), (3, 2), (1, 3), (2, 3)):
                    put(f, x + dx, y + dy, (210, 242, 252, 150))
                put(f, x + 1, y + 1, (255, 255, 255, 220))
        frames.append(f)
    return frames


def caustics():
    frames = []
    w = 2 * math.pi / S
    for k in range(S):
        t = 2 * math.pi * k / S
        f = blank()
        for y in range(S):
            for x in range(S):
                v = (math.sin(x * w * 2 + t) + math.sin(y * w * 2 - t) + math.sin((x + y) * w + 2 * t)
                     + math.sin((x - y) * w * 3 - t))
                a = max(0.0, (v - 1.4) / 1.6)
                if a > 0:
                    f.putpixel((x, y), (225, 250, 255, int(40 + 110 * min(1.0, a))))
        frames.append(f)
    return frames


def meniscus():
    f = blank()
    for y in range(S):
        a = 230 if y < 5 else (170 if y < 11 else 110)
        for x in range(S):
            f.putpixel((x, y), (255, 255, 255, a - (12 if (x + y) % 5 == 0 else 0)))
    return [f]


def motes():
    rnd = random.Random(23)
    spots = [(rnd.randrange(S), rnd.randrange(S), rnd.random() * 2 * math.pi) for _ in range(9)]
    frames = []
    for k in range(S):
        f = blank()
        for x, y, ph in spots:
            dx = int(round(math.sin(ph + 2 * math.pi * k / S)))
            shade = 200 if ph < math.pi else 120
            put(f, x + dx, y + k, (shade, shade, int(shade * 0.85), 170))
        frames.append(f)
    return frames


def pulse():
    frames = []
    for k in range(S):
        f = blank()
        for y in range(S):
            d = min((y - k) % S, (k - y) % S)
            a = max(0.0, 1 - d / 3.5) ** 2
            if a > 0:
                for x in range(S):
                    f.putpixel((x, y), (235, 255, 255, int(85 * a)))
        frames.append(f)
    return frames


def solid(rgb, edge):
    f = blank()
    for y in range(S):
        for x in range(S):
            border = x in (0, S - 1) or y in (0, S - 1)
            f.putpixel((x, y), (edge if border else rgb) + (255,))
    return [f]


def glow_on():
    frames = []
    for k in range(8):
        f = blank()
        b = 0.85 + 0.15 * math.sin(2 * math.pi * k / 8)
        for y in range(S):
            for x in range(S):
                c = 1 - abs(y - 7.5) / 9
                f.putpixel((x, y), (int(110 * b * c + 60), int(240 * b * c + 15), int(255 * b * c), 255))
        frames.append(f)
    return frames


def embers():
    rnd = random.Random(5)
    spots = [(rnd.randrange(S), rnd.randrange(S), rnd.randrange(S)) for _ in range(6)]
    frames = []
    for k in range(S):
        f = blank()
        for x, y, ph in spots:
            life = (k + ph) % S
            if life < 10:
                bright = 1 - life / 10
                col = (255, int(150 + 90 * bright), int(40 + 80 * bright), int(120 + 135 * bright))
                put(f, x + (1 if life > 5 else 0), y + k, col)
        frames.append(f)
    return frames


def frost():
    rnd = random.Random(3)
    f = blank()
    for _ in range(26):
        put(f, rnd.randrange(S), rnd.randrange(S), (235, 248, 255, rnd.randrange(50, 110)))
    for _ in range(7):
        x, y = rnd.randrange(S), rnd.randrange(S)
        dx, dy = rnd.choice([(1, 1), (1, -1), (1, 0), (0, 1)])
        for i in range(rnd.randrange(2, 5)):
            put(f, x + dx * i, y + dy * i, (240, 252, 255, 150))
    for x in range(S):
        for y in (0, S - 1):
            put(f, x, y, (230, 246, 255, 120))
    return [f]


def droplets():
    rnd = random.Random(9)
    still = [(rnd.randrange(S), rnd.randrange(S)) for _ in range(6)]
    moving = [(rnd.randrange(S), rnd.randrange(S)) for _ in range(2)]
    frames = []
    for k in range(S):
        f = blank()
        for x, y in still:
            put(f, x, y, (215, 238, 248, 150))
            put(f, x, y + 1, (170, 210, 228, 120))
        for x, y in moving:
            put(f, x, y + k - 1, (200, 228, 240, 60))
            put(f, x, y + k, (225, 245, 252, 170))
            put(f, x, y + k + 1, (180, 215, 232, 140))
        frames.append(f)
    return frames


def haze():
    frames = []
    w = 2 * math.pi / S
    for k in range(S):
        t = 2 * math.pi * k / S
        f = blank()
        for y in range(S):
            for x in range(S):
                v = math.sin(x * w + math.sin(y * w * 2 - t) * 1.5) + math.sin(y * w - t)
                a = 0.5 + 0.25 * v
                f.putpixel((x, y), (255, int(130 + 40 * a), 60, int(18 + 50 * a)))
        frames.append(f)
    return frames


def main():
    os.makedirs(OUT, exist_ok=True)
    save("glass", [from_rows(GLASS, GLASS_PAL)])
    # The 16x4 rail repeated to 16x16: a non-square sprite would cap the whole block atlas's mip levels.
    save("rail", [from_rows(RAIL * 4, RAIL_PAL)])
    save("bubbles", bubbles(), 2)
    save("caustics", caustics(), 3, True)
    save("meniscus", meniscus())
    save("motes", motes(), 6)
    save("pulse", pulse(), 2, True)
    save("gauge_lit", solid((120, 245, 255), (60, 200, 230)))
    save("gauge_dim", solid((52, 66, 74), (40, 50, 56)))
    save("glow_on", glow_on(), 4, True)
    save("glow_off", solid((34, 70, 80), (26, 52, 60)))
    save("embers", embers(), 2)
    save("frost", [frost()[0]])
    save("droplets", droplets(), 5)
    save("haze", haze(), 3, True)


if __name__ == "__main__":
    main()
