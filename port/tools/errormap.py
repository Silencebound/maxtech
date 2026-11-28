#!/usr/bin/env python3
"""Turns the errorMap javac log into port/errors.md and port/ownership.txt (port/README.md, "Error map").

    python3 -I port/tools/errormap.py --run [--single] [--symbols] [--write-ownership]   (what errormap.sh calls)
    python3 -I port/tools/errormap.py port/build/errormap.log [--symbols] [--write-ownership]  (an existing javac log)
    python3 -I port/tools/errormap.py --run --mod storage [--single] [--symbols]   (Storage: port/storage-errors.md)

--run compiles with the javac inputs that `gradlew errorMapInputs` wrote. The default is the per-package mode: one javac
run per work package that names only that package's files (ownership.txt) and finds the rest through -sourcepath; only
errors inside the package's own files count. One javac over everything (--single) stops attributing once a type is
missing, so a package whose files depend on another package's broken files shows few errors (saving: 75 reported vs
about 480 compiled alone). Trade-offs: per-package takes five javac runs (run in parallel), an error in a file another
package owns is not counted for you, and counts overlap in effect (fixing one shim removes errors in several runs).
--single is the quick all-in-one view and lists files that have no owner yet. --write-ownership implies --single,
since owners are derived from the errors.

Each error gets an area from its message, source line and symbol (AREAS, first match wins). Each file with errors gets
one work package: the package of its dominant area, unless a path rule (PATH_OWNER) says otherwise. errors.md lists
both views. --write-ownership (re)writes port/ownership.txt, the file -> package map convert.py checks rules against;
do that only when the split is (re)planned, since agents work from it.
"""

from __future__ import annotations

import collections
import concurrent.futures
import os
import re
import subprocess
import sys
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
OUT = PORT / "out"

PACKAGES = ("transfer", "rendering", "gui", "saving", "other")

# area -> (package, regex). Order matters: the first match wins.
AREAS = [
    ("java", "other", r"preview feature|unnamed variable"),
    ("transfer", "transfer", r"shim\.transfer|TransactionContext|\bTransaction\b|ResourceHandler|ItemResource|"
                             r"FluidResource|EnergyHandler|SnapshotJournal|TransferPreconditions|ResourceStack|"
                             r"ItemAccess|PlayerInventoryWrapper|Capabilities\.(Item|Fluid|Energy)|ValueIOSerializable|"
                             r"FluidStackTemplate|FluidInstance|SimpleFluidContent"),
    ("saving", "saving", r"ValueInput|ValueOutput|TagValue(Input|Output)|ProblemReporter|get(String|Int|Boolean|Long|"
                         r"Float|Double|Byte|Short)Or\b|get(Compound|List)OrEmpty|loadAdditional|saveAdditional|"
                         r"\bread\(ValueInput|storeNullable|NbtOps|getOrEmpty"),
    ("gametest", "saving", r"GameTest|gametest|TestEnvironment|FunctionGameTestInstance|TestData\b|GameTestHelper|"
                           r"RegisterGameTestsEvent|TestInstance"),
    ("gui", "gui", r"GuiGraphics|Extractor|MouseButton|InputWithModifier|KeyEvent|CharacterEvent|extract[A-Z]|"
                   r"\bScreen\b|AbstractContainerScreen|Tooltip|renderWidget|AbstractWidget|\bButton\b|EditBox|"
                   r"ContainerInput|ClickType|mouseClicked|keyPressed|charTyped|NarrationElementOutput"),
    ("rendering", "rendering", r"RenderPipeline|[Bb]laze3[Dd]|SubmitNode|SubmitCustomGeometry|RenderType|BlockModel|"
                               r"ItemModel|QuadCollection|BakedQuad|ModelBaker|BlockStateModel|ItemStackRenderState|"
                               r"RenderState|PoseStack|MultiBufferSource|VertexConsumer|TextureAtlas|SpriteGetter|"
                               r"SpecialModel|Unbaked|client\.data\.models|ModelProvider|TextureSlot|Quadrant|"
                               r"CuboidFace|Transparency|BlockTintSource|ItemTintSource|AtlasIds|LightCoords|"
                               r"CameraRenderState|BlockDisplayContext|setLineWidth|Renderer\b|ModelData|"
                               r"AddClientReloadListenersEvent|VanillaClientListeners|client\.fluid"),
    ("recipes", "other", r"Recipe|PlacementInfo|SlotDisplay|Ingredient"),
    ("components", "other", r"DataComponent|TooltipDisplay|ItemStackTemplate|TypedEntityData|CustomData"),
    ("naming", "other", r"ResourceLocation|ResourceKey|Identifier"),
    ("permissions", "other", r"Permission|permissions\(\)"),
    ("network", "other", r"StreamCodec|PacketDistributor|Payload|RegistryFriendlyByteBuf"),
    ("override", "other", r"does not override|cannot be applied to given types|is not abstract and does not override"),
]

# Path rules that beat the dominant area (regex on the path below port/out).
PATH_OWNER = [
    (r"/shim/transfer/", "transfer"),
    (r"/shim/(saving|gametest)/", "saving"),
    (r"/shim/client/gui/", "gui"),
    (r"/shim/client/render/", "rendering"),
    (r"/shim/", "other"),
    (r"/src/gametest/", "saving"),
    (r"/client/.*(render|model|Render|Model|Baked|Atlas|Particle)", "rendering"),
    (r"/datagen/.*(Model|BlockState|ItemModel)", "rendering"),
    (r"/client/.*(screen|Screen|gui|Gui|widget|Widget|Overlay|Hud|Tooltip)", "gui"),
]

# javac prints /abs/Foo.java or C:\abs\Foo.java; messages are English because the run passes -J-Duser.language=en.
ERR = re.compile(r"^((?:[A-Za-z]:)?[^:]+?\.java):(\d+): error: (.*)$")


def parse(text: str, only: set[str] | None = None) -> list[dict]:
    """The errors in javac's output; `only` keeps those in files of that set (paths below out/, '/' separators)."""
    errs, cur = [], None
    for line in text.splitlines():
        m = ERR.match(line)
        if m:
            cur = {"file": m.group(1).replace("\\", "/"), "line": int(m.group(2)), "msg": m.group(3), "src": "", "sym": ""}
            errs.append(cur)
            continue
        if cur is None:
            continue
        s = re.match(r"\s+symbol:\s+(.*)$", line)
        if s:
            cur["sym"] = s.group(1)
        elif not cur["src"] and line.strip() and not line.strip().startswith("^") and not line.startswith("  location"):
            cur["src"] = line.strip()
    out = _ci(OUT.as_posix()) + "/"
    for e in errs:
        f = e["file"]
        e["rel"] = f[len(out):] if _ci(f).startswith(out) else f
    if only is not None:
        errs = [e for e in errs if e["rel"] in only]
    for e in errs:
        text =f"{e['msg']} {e['src']} {e['sym']}"
        e["area"], e["pkg"] = "other", "other"
        for area, pkg, rx in AREAS:
            if re.search(rx, text):
                e["area"], e["pkg"] = area, pkg
                break
    return errs


def owner(rel: str, file_errs: list[dict]) -> str:
    for rx, pkg in PATH_OWNER:
        if re.search(rx, rel):
            return pkg
    counts = collections.Counter(e["pkg"] for e in file_errs)
    # "other" only wins when nothing more specific is in the file: a file with any transfer/saving/... work goes there.
    specific = [(n, p) for p, n in counts.items() if p != "other"]
    return max(specific)[1] if specific else "other"


def owner_without_errors(rel: str) -> str:
    """Owner of a file that compiles now: path rules, else the package whose area patterns its text matches most."""
    for rx, pkg in PATH_OWNER:
        if re.search(rx, rel):
            return pkg
    text = (OUT / rel).read_text(encoding="utf-8", errors="replace")
    counts = collections.Counter()
    for _, pkg, rx in AREAS:
        if pkg != "other":
            counts[pkg] += len(re.findall(rx, text))
    best = counts.most_common(1)
    return best[0][0] if best and best[0][1] >= 3 else "other"


def short(rel: str) -> str:
    return re.sub(r"^lib/(common|neoforge)/src/(\w+)/java/dev/silencebound/maxtech/lib/", r"\1/\2: ", rel)


_ci = (lambda s: s.lower()) if os.name == "nt" else (lambda s: s)


def read_ownership() -> dict[str, str]:
    owners = {}
    for line in (PORT / "ownership.txt").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            pkg, _, path = line.partition(" ")
            owners[path.strip()] = pkg
    return owners


def javac_cmd(work: Path, extra: list[str], mod: str = "lib") -> list[str]:
    ne = PORT / f"projects/{mod}-neoforge/build/errorMap"
    cm = PORT / f"projects/{mod}-common/build/errorMap"
    jars = sorted({line for f in (cm / "classpath.txt", ne / "classpath.txt")
                   for line in f.read_text(encoding="utf-8").splitlines() if line})
    if mod != "lib":
        # the generated Lib's classes; Storage's own come from source, never from a stale build
        jars += sorted({line for f in (cm / "classdirs.txt", ne / "classdirs.txt")
                        for line in f.read_text(encoding="utf-8").splitlines()
                        if line and "/projects/lib-" in line.replace("\\", "/") and Path(line).is_dir()})
    return [(ne / "javac.txt").read_text(encoding="utf-8").strip(), "-J-Duser.language=en", "-J-Duser.country=US",
            "-XDshould-stop.ifError=FLOW", "-proc:none", "-encoding", "UTF-8", "-Xmaxerrs", "100000", "-Xmaxwarns", "0", "-nowarn",
            "-implicit:none", "-d", str(work / "classes"), "-cp", os.pathsep.join(jars), *extra]


def source_files(mod: str = "lib") -> list[str]:
    files = []
    for proj in (f"{mod}-common", f"{mod}-neoforge"):
        files += (PORT / "projects" / proj / "build/errorMap/sources.txt").read_text(encoding="utf-8").splitlines()
    return [f.replace("\\", "/") for f in files if f]


def run_javac(cmd: list[str], work: Path) -> str:
    # javac drops a javac.<date>.args file into the working directory on errors; exit status 1 is the point of the run.
    p = subprocess.run(cmd, cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                       encoding="utf-8", errors="replace")
    return p.stdout


def run_all(single: bool) -> list[dict]:
    work = PORT / "build" / "errorMap"
    work.mkdir(parents=True, exist_ok=True)
    files = source_files()
    out = _ci(OUT.as_posix()) + "/"
    rels = {f: (f[len(out):] if _ci(f).startswith(out) else f) for f in files}
    if single:
        (work / "sources.txt").write_text("\n".join(files) + "\n", encoding="utf-8")
        text = run_javac(javac_cmd(work, [f"@{work / 'sources.txt'}"]), work)
        (PORT / "build" / "errormap.log").write_text(text, encoding="utf-8")
        return parse(text)
    # one run per package: its files explicit, everything else on the sourcepath (resolved, not reported)
    dirs = sorted({f[:f.index("/java/") + 5] for f in files if "/java/" in f})
    owners = read_ownership()
    mine = {p: [f for f in files if owners.get(rels[f], "other") == p] for p in PACKAGES}

    def one(pkg: str) -> tuple[str, str]:
        w = work / pkg
        w.mkdir(exist_ok=True)
        (w / "sources.txt").write_text("\n".join(mine[pkg]) + "\n", encoding="utf-8")
        return pkg, run_javac(javac_cmd(w, ["-sourcepath", os.pathsep.join(dirs), f"@{w / 'sources.txt'}"]), w)

    errs, logs = [], []
    with concurrent.futures.ThreadPoolExecutor(len(PACKAGES)) as pool:
        for pkg, text in pool.map(one, PACKAGES):
            logs.append(text)
            errs += parse(text, {rels[f] for f in mine[pkg]})
    (PORT / "build" / "errormap.log").write_text("\n".join(logs), encoding="utf-8")
    return errs


# --- Storage (--mod storage, port/storage-errors.md) ------------------------------------------------------------------
# Storage has no work packages yet, so each file gets an area from its path (first match wins, regex on the path below
# port/out). The default mode runs one javac per area on that area's files, like Lib's per-package mode, so one area's
# broken files do not hide another's errors; Lib comes in as compiled classes (classdirs.txt).
STORAGE_AREAS = [
    ("gametest", r"/src/gametest/"),
    ("compat", r"/compat/"),
    ("datagen", r"/datagen/"),
    ("unit tests", r"/src/test/"),
    ("rendering", r"/client/.*(render|model|Render|Model|Bak|Quad|Atlas|Particle|Painter)"),
    ("gui", r"/client/|/storage/(terminal|devicetab)/"),
    ("saving", r"/storage/save/"),
    ("network", r"/storage/(network|cable|subnet|p2p|cluster|hub|controller|energy|signal|redstone|togglebus|"
                r"priority)/"),
    ("storage", r"/storage/(cell|drive|storageblock|storagechest|bulk|creative)/"),
    ("transfer", r"/storage/(port|ioport|iface|bridge|part)/"),
    ("crafting", r"/storage/crafting/"),
    ("logic", r"/storage/(logic|sensor|condition|display)/"),
    ("tools", r"/storage/tool/"),
    ("other", r""),
]


def storage_area(rel: str) -> str:
    return next(a for a, rx in STORAGE_AREAS if re.search(rx, rel))


def storage_short(rel: str) -> str:
    return re.sub(r"^storage/(common|neoforge)/src/(\w+)/java/dev/silencebound/maxtech/storage/", r"\1/\2: ", rel)


def storage_run(single: bool) -> tuple[list[dict], list[str]]:
    work = PORT / "build" / "errorMap-storage"
    work.mkdir(parents=True, exist_ok=True)
    files = source_files("storage")
    out = _ci(OUT.as_posix()) + "/"
    rels = {f: (f[len(out):] if _ci(f).startswith(out) else f) for f in files}
    if single:
        (work / "sources.txt").write_text("\n".join(files) + "\n", encoding="utf-8")
        text = run_javac(javac_cmd(work, [f"@{work / 'sources.txt'}"], "storage"), work)
        (PORT / "build" / "errormap-storage.log").write_text(text, encoding="utf-8")
        return parse(text), list(rels.values())
    dirs = sorted({f[:f.index("/java/") + 5] for f in files if "/java/" in f})
    areas = [a for a, _ in STORAGE_AREAS]
    mine = {a: [f for f in files if storage_area(rels[f]) == a] for a in areas}

    def one(area: str) -> tuple[str, str]:
        w = work / area.replace(" ", "-")
        w.mkdir(exist_ok=True)
        (w / "sources.txt").write_text("\n".join(mine[area]) + "\n", encoding="utf-8")
        cmd = javac_cmd(w, ["-sourcepath", os.pathsep.join(dirs), f"@{w / 'sources.txt'}"], "storage")
        return area, run_javac(cmd, w)

    errs, logs = [], []
    # Each run reads the rest of Storage through the sourcepath; four at a time keeps the memory in bounds.
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        for area, text in pool.map(one, [a for a in areas if mine[a]]):
            logs.append(text)
            errs += parse(text, {rels[f] for f in mine[area]})
    (PORT / "build" / "errormap-storage.log").write_text("\n".join(logs), encoding="utf-8")
    return errs, list(rels.values())


def storage_main(single: bool) -> int:
    errs, rels = storage_run(single)
    by_file = collections.defaultdict(list)
    for e in errs:
        by_file[e["rel"]].append(e)
    main_root = PORT.parent
    unchanged = {r for r in rels if (main_root / r).is_file()
                 and (main_root / r).read_bytes() == (OUT / r).read_bytes()}

    def clean(fs: list[str]) -> tuple[int, int]:
        ok = [r for r in fs if r not in by_file]
        return sum(1 for r in ok if r in unchanged), sum(1 for r in ok if r not in unchanged)

    same, changed = clean(rels)
    lines = [f"total {len(errs)} errors in {len(by_file)} of {len(rels)} files"
             f" ({'one javac run over all files' if single else 'per area, own files only'})",
             f"clean files: {same} unchanged from main, {changed} changed by rules", "",
             "by area (path):   errors  files with errors / files  clean unchanged  clean changed"]
    for a, _ in STORAGE_AREAS:
        fs = [r for r in rels if storage_area(r) == a]
        bad = [r for r in fs if r in by_file]
        s, c = clean(fs)
        lines.append(f"  {a:12s} {sum(len(by_file[r]) for r in bad):7d} {len(bad):10d} / {len(fs):4d} {s:16d} {c:14d}")
    kinds = collections.defaultdict(list)
    for e in errs:
        kinds[e["area"]].append(e)
    lines.append("by kind (message, as Lib's areas):")
    for k, es in sorted(kinds.items(), key=lambda kv: -len(kv[1])):
        lines.append(f"  {k:12s} {len(es):5d} errors {len({e['rel'] for e in es}):4d} files")
    lines.append("")
    for a, _ in STORAGE_AREAS:
        fs = sorted((r for r in by_file if storage_area(r) == a), key=lambda r: -len(by_file[r]))
        if fs:
            lines.append(f"[{a}]")
        for r in fs:
            c = collections.Counter(e["area"] for e in by_file[r])
            lines.append(f"  {len(by_file[r]):4d}  {storage_short(r)}  ({', '.join(f'{k} {n}' for k, n in c.most_common())})")
    if "--symbols" in sys.argv:
        lines.append("[symbols all areas]")
        for s, n in collections.Counter((e["sym"] or e["msg"])[:100] for e in errs).most_common(30):
            lines.append(f"  {n:4d} {s}")
        for a, _ in STORAGE_AREAS:
            es = [e for e in errs if storage_area(e["rel"]) == a]
            if es:
                lines.append(f"[symbols {a}]")
            for s, n in collections.Counter((e["sym"] or e["msg"])[:100] for e in es).most_common(15):
                lines.append(f"  {n:4d} {s}")
    print("\n".join(lines))
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    single = "--single" in sys.argv or "--write-ownership" in sys.argv
    if "--mod" in sys.argv and sys.argv[sys.argv.index("--mod") + 1:][:1] == ["storage"]:
        return storage_main("--single" in sys.argv)
    if "--run" in sys.argv:
        errs = run_all(single)
    else:
        errs = parse(Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace"))
        single = True
    by_file = collections.defaultdict(list)
    for e in errs:
        by_file[e["rel"]].append(e)
    if single:
        owners = {f: owner(f, es) for f, es in by_file.items()}
    else:
        listed = read_ownership()
        owners = {f: listed.get(f, "other") for f in by_file}

    lines = [f"total {len(errs)} errors in {len(by_file)} files"
             f" ({'one javac run over all files' if single else 'per package, own files only'})", ""]
    lines.append("by area:")
    areas = collections.defaultdict(list)
    for e in errs:
        areas[e["area"]].append(e)
    for a, es in sorted(areas.items(), key=lambda kv: -len(kv[1])):
        lines.append(f"  {a:12s} {len(es):5d} errors {len({e['rel'] for e in es}):4d} files")
    lines.append("by package (file owner):")
    for p in PACKAGES:
        fs = [f for f, o in owners.items() if o == p]
        lines.append(f"  {p:12s} {sum(len(by_file[f]) for f in fs):5d} errors {len(fs):4d} files")
    lines.append("")
    for p in PACKAGES:
        lines.append(f"[{p}]")
        for f in sorted((f for f, o in owners.items() if o == p), key=lambda f: -len(by_file[f])):
            c = collections.Counter(e["area"] for e in by_file[f])
            lines.append(f"  {len(by_file[f]):4d}  {short(f)}  ({', '.join(f'{a} {n}' for a, n in c.most_common())})")
    if "--symbols" in sys.argv:
        for a, es in areas.items():
            lines.append(f"[symbols {a}]")
            for s, n in collections.Counter((e["sym"] or e["msg"])[:100] for e in es).most_common(25):
                lines.append(f"  {n:4d} {s}")
    print("\n".join(lines))

    if "--write-ownership" in sys.argv:
        out = ["# file -> work package (port/errors.md). Generated by tools/errormap.py --write-ownership; edit by hand",
               "# only to move a file between packages (both owners agree). convert.py warns when a package's rules",
               "# change a file owned by another package. Every generated .java file is listed; a new main file",
               "# belongs to `other` until moved."]
        total = dict(owners)
        for f in (OUT / "lib").rglob("*.java"):
            rel = f.relative_to(OUT).as_posix()
            total.setdefault(rel, owner_without_errors(rel))
        out += [f"{total[f]:10s} {f}" for f in sorted(total)]
        (PORT / "ownership.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
