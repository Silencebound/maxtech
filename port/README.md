# MaxTech Lib for Minecraft 1.21.1 (generated backport)

Pilot of 2026-11-28, branch `port/1.21.1`, never merged into main. Main stays 26.1.2 and is the only source anyone
edits by hand; the 1.21.1 sources are **generated** from it:

```
main lib/**  --convert.py-->  port/out/lib/**   (copy + rules)
                               + port/shim/**     (Lib-owned adapters: new files only)
                               + port/override/** (whole-file replacements, only where rules can't)
port/ (own Gradle build, NeoForge 21.1.268, Java 21)  builds  port/out
```

`port/out` is never committed or edited. A fix belongs in one of: a rule (`port/rules/<package>.py`), a shim, an
override, or main itself when the 26.1 code can be written so both versions work.

## Commands

Run from the repository root (needs Python 3.9+, `-I` keeps the interpreter isolated):

```
python3 -I port/convert.py                 # regenerate port/out from this checkout's lib/
python3 -I port/convert.py --main ../other # ... from another main checkout
python3 -I port/convert.py --check         # exit 1 on warnings (stale overrides, rule ownership, shadowing shims)
(cd port && ./gradlew compileJava --continue)          # normal 1.21.1 build of the generated tree
(cd port && ./gradlew :lib:neoforge:runGameTestServer) # GameTests, once Lib compiles
(cd port && ./gradlew :lib:neoforge:runClient)         # dev client
port/tools/errormap.sh                     # regenerate, then one javac per work package; summary on stdout
port/tools/errormap.sh --single            # ... or ONE javac over everything (faster, can hide errors)
port/tools/errormap.sh --symbols           # ... plus the most common missing symbols per area
```

Gradle's `compileJava` stops at the first failing source set (common's api), so while errors remain use the error map:
it compiles all seven source sets (common api/main/test, neoforge api/main/gametest/test) and groups the errors by area,
work package and file (`port/tools/errormap.py`). By default it runs javac once per package on that package's files only
(the rest via `-sourcepath`) and counts only errors in those files, so a package's errors are not masked by another's
broken files (one javac over everything reported 75 errors for saving, about 480 compiled alone). The cost: five javac
runs, and an error in a file another package owns shows for its owner only. `--single` is the one-run view. The script
runs in Git Bash on Windows too (`python3` or `python`, `;` classpath separator, English javac messages).
`port/errors.md` is its output plus the plan.

## Layout

| path | what |
|---|---|
| `convert.py` | the generator (copy, rules, shim, override, report in `out/.port/report.txt`) |
| `rules/framework.py` | rule types: `Rename`, `ImportMove`, `Regex`, `Transform`; Java text is split into code, comments and literals so renames never touch strings |
| `rules/core.py` | rules every file needs (naming, package moves, contract moves to shims, `_` for Java 21); applied first |
| `rules/<package>.py` | one module per work package (`transfer`, `rendering`, `gui`, `saving`, `other`); applied in name order after core |
| `shim/lib/**` | Lib-owned adapters under `dev.silencebound.maxtech.lib.shim`, laid out like `lib/` (e.g. `shim/lib/common/src/api/java/...`) |
| `shim/.../shim/transfer/**` | NeoForge 26.1.2's transfer api, vendored and relocated (`NOTICE-transfer.md`, `tools/vendor_transfer.py`) |
| `override/lib/**` | whole-file replacements, same paths as `lib/` |
| `ownership.txt` | generated file -> work package (`errors.md`, "Ownership") |
| `projects/lib-common`, `projects/lib-neoforge` | the Gradle projects; their sources are `out/lib/<piece>/src/<set>/java` |
| `gradle/libs.versions.toml` | 1.21.1 dependency versions (main's catalog stays 26.1.2) |

## Overrides

An override replaces a generated file completely, so it must notice when main's file changes. Its first line is

```
// port-override: base=<first 16 hex chars of sha256 of main's lib/... file>
```

(`shasum -a 256 lib/.../Foo.java | cut -c1-16`). convert.py warns when main's file no longer matches; re-check the
override against main's change, then update the hash. `Foo.java.port-remove` (same header) drops a generated file.
Prefer rules: an override is a second copy of the file that must be kept in step by hand.

## Rules

```python
from framework import ImportMove, Regex, Rename, Transform

RULES = [
    Rename("OldName", "NewName", files=("lib/common/src/main/java/**/Foo.java",)),
    Regex(r"\.oldCall\(", ".newCall(", files=(...)),
]
```

Scope each rule to the package's own files (`ownership.txt`); convert warns when a package rule changes another
package's file. A move every package needs goes into `core.py`, in its own small commit.

## Shims and the transfer api

Shim types that replace 26.1-only types keep 26.1's simple name and public shape (`errors.md`, "Contracts"), so code
generated from main compiles against them unchanged and the packages can work in parallel. The vendored transfer api
keeps NeoForge's LGPL-2.1-only headers plus a modification notice; `NOTICE-transfer.md` travels in the jar's META-INF.
Re-vendoring (`python3 -I port/tools/vendor_transfer.py <neoforge-26.1.2.x-sources.jar>`) overwrites the hand edits
made for 1.21.1, so diff before committing one.

## Differences from 26.1

Where the 1.21.1 build intentionally differs from main:

- `ConduitItemModels.shell` takes an `ItemModelProvider`.
- `MenuClient` and `GuideWebExport` are removed.
- `MachineRecipeType.bookCategory` is dropped.
- `CraftingGridMenus` uses the non-untrusted item codec.
- GameTest environment kinds `clock_time`, `game_rules` and `timelines` are dropped.
- The GameTest server has no `--tests` filter: `-Pgametest=<namespace>` (default `maxtech_lib`) sets `neoforge.enabledGameTestNamespaces`; a `:pattern` suffix is ignored, so a whole namespace runs.

## Versions

NeoForge 21.1.268, GuideME 21.1.22, Jade 15.10.6, KubeJS 2101.7.2, FTB Teams 2101.1.12, JSpecify 1.0.0 (compile
only; 26.1 brings it with Minecraft). Error Prone, NullAway, Spotless and `-Werror` are off here: the code is checked
on main, and the generated copy would only repeat those findings. The jar's version is main's `lib_version` plus
`+mc1.21.1`.
