#!/usr/bin/env python3
"""Generates the 1.21.1 sources of MaxTech Lib from main's 26.1.2 sources (port/README.md).

    python3 -I port/convert.py [--main <checkout>] [--check]

1. Copies <main>/lib/{common,neoforge}/src/** to port/out/lib/... (port/out is wiped first).
2. Applies the rules: port/rules/core.py first, then every other port/rules/<package>.py in name order.
3. Overlays port/shim/** (new, Lib-owned files; a shim may not replace a generated file).
4. Overlays port/override/** (whole-file replacements of generated files). An override's first line is
   `// port-override: base=<sha256 prefix>` of the main file it replaces; when main's file changes, convert warns so
   the override is re-checked. A file `<name>.port-remove` removes the generated <name>.
5. Writes port/out/.port/report.txt: rule hit counts, overrides, warnings.

--check exits 1 on any warning (stale override, override without a main file, shim shadowing a generated file).
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import shutil
import sys
from pathlib import Path

PORT = Path(__file__).resolve().parent
OUT = PORT / "out"
PIECES = ("lib/common/src", "lib/neoforge/src")
REMOVE = ".port-remove"
HEADER = "// port-override: base="

sys.path.insert(0, str(PORT / "rules"))
import framework  # noqa: E402  (our own module, loaded from port/rules explicitly)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def load_rules() -> list[tuple[str, list[framework.Rule]]]:
    names = ["core"] + sorted(p.stem for p in (PORT / "rules").glob("*.py") if p.stem not in ("core", "framework"))
    loaded = []
    for name in names:
        spec = importlib.util.spec_from_file_location(f"port_rules_{name}", PORT / "rules" / f"{name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        loaded.append((name, list(getattr(module, "RULES", []))))
    return loaded


def copy_main(main: Path) -> list[Path]:
    copied = []
    for piece in PIECES:
        src = main / piece
        if not src.is_dir():
            sys.exit(f"convert: {src} not found (is --main a MaxTech checkout?)")
        for f in sorted(src.rglob("*")):
            if f.is_file() and "build" not in f.relative_to(src).parts and ".cache" not in f.parts:
                dest = OUT / piece / f.relative_to(src)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dest)
                copied.append(dest)
    return copied


def load_ownership() -> dict[str, str]:
    path = PORT / "ownership.txt"
    if not path.is_file():
        return {}
    owners = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            pkg, rel = line.split(None, 1)
            owners[rel.strip()] = pkg
    return owners


def apply_rules(files: list[Path], rules: list[tuple[str, list[framework.Rule]]], warnings: list[str]) -> None:
    flat = [(name, r) for name, rs in rules for r in rs]
    owners = load_ownership()
    for f in files:
        rel = f.relative_to(OUT).as_posix()
        todo = [(name, r) for name, r in flat if r.matches(rel)]
        if not todo:
            continue
        text = original = f.read_text(encoding="utf-8")
        for name, rule in todo:
            before = text
            text = rule.apply(text, rel)
            owner = owners.get(rel)
            if text != before and name != "core" and owner is not None and owner != name:
                warnings.append(f"rules/{name}.py ({rule.describe()}) changed {rel}, owned by {owner}; "
                                f"scope the rule with files=")
        if text != original:
            f.write_text(text, encoding="utf-8")


def overlay(main: Path, warnings: list[str], report: list[str]) -> None:
    shim = PORT / "shim"
    for f in sorted(shim.rglob("*")) if shim.is_dir() else []:
        if not f.is_file() or f.name == ".gitkeep":
            continue
        rel = f.relative_to(shim)
        dest = OUT / rel
        if dest.exists():
            warnings.append(f"shim {rel} shadows a generated file; move it to port/override")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dest)
        report.append(f"shim      {rel}")

    override = PORT / "override"
    for f in sorted(override.rglob("*")) if override.is_dir() else []:
        if not f.is_file() or f.name == ".gitkeep":
            continue
        rel = f.relative_to(override)
        remove = f.name.endswith(REMOVE)
        if remove:
            rel = rel.with_name(f.name[: -len(REMOVE)])
        base = main / rel
        if not base.is_file():
            warnings.append(f"override {rel}: main has no such file any more")
        else:
            first = f.read_text(encoding="utf-8").split("\n", 1)[0].strip()
            recorded = first[len(HEADER):].strip() if first.startswith(HEADER) else None
            if recorded is None:
                warnings.append(f"override {rel}: first line must be '{HEADER}{sha(base)}'")
            elif recorded != sha(base):
                warnings.append(f"override {rel}: main's file changed since the override was written "
                                f"(base {recorded}, now {sha(base)}); re-check it, then update the header")
        dest = OUT / rel
        if remove:
            dest.unlink(missing_ok=True)
            report.append(f"removed   {rel}")
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)
            report.append(f"override  {rel}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--main", type=Path, default=PORT.parent, help="main checkout to convert (default: this one)")
    parser.add_argument("--check", action="store_true", help="exit 1 on warnings")
    args = parser.parse_args()
    main_dir = args.main.resolve()

    if OUT.exists():
        shutil.rmtree(OUT)
    files = copy_main(main_dir)
    rules = load_rules()
    warnings: list[str] = []
    apply_rules(files, rules, warnings)

    report = [f"main: {main_dir}", f"files: {len(files)}", "", "rules (hits):"]
    for name, rs in rules:
        for r in rs:
            report.append(f"  {name:10s} {r.hits:6d}  {r.describe()}")
    report.append("")
    overlay(main_dir, warnings, report)
    report += ["", "warnings:"] + [f"  {w}" for w in warnings]

    (OUT / ".port").mkdir(parents=True, exist_ok=True)
    (OUT / ".port" / "report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    for w in warnings:
        print("convert: warning:", w, file=sys.stderr)
    print(f"convert: {len(files)} files -> {OUT.relative_to(PORT.parent)}, {len(warnings)} warnings "
          f"(report: port/out/.port/report.txt)")
    return 1 if args.check and warnings else 0


if __name__ == "__main__":
    sys.exit(main())
