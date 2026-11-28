"""Rules of work package `saving`: saving (ValueInput/ValueOutput) and GameTests (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.
"""

import re
from pathlib import Path

from framework import ImportMove, Regex, Rename, Transform, split_java  # noqa: F401

_PORT = Path(__file__).resolve().parents[1]
_SHIM = "dev.silencebound.maxtech.lib.shim"
_SAVING = f"{_SHIM}.saving"


def _owned(package: str) -> tuple[str, ...]:
    lines = (_PORT / "ownership.txt").read_text(encoding="utf-8").splitlines()
    pairs = (line.split() for line in lines if line.strip() and not line.startswith("#"))
    return tuple(p for owner, p in pairs if owner == package and p.endswith(".java"))


OWN = _owned("saving")


# --- receiver calls: x.m(args) -> Helper.m(x, args) -------------------------------------------------------------

def _code_mask(text: str) -> list[bool]:
    """True for each character that is code (not inside a comment or a literal)."""
    mask: list[bool] = []
    for kind, part in split_java(text):
        mask.extend([kind == "code"] * len(part))
    return mask


_OPEN = {")": "(", "]": "["}


def _receiver_start(text: str, mask: list[bool], dot: int) -> int:
    """Start of the receiver expression that ends just before `dot` (a chain of names, calls, casts, indexing)."""
    i = dot - 1
    while i >= 0 and text[i].isspace():
        i -= 1
    while i >= 0:
        c = text[i]
        if mask[i] and c in _OPEN:
            depth, close, open_ = 0, c, _OPEN[c]
            while i >= 0:
                if mask[i] and text[i] == close:
                    depth += 1
                elif mask[i] and text[i] == open_:
                    depth -= 1
                    if depth == 0:
                        break
                i -= 1
            i -= 1
            # a call's or an index's name, if any, belongs to the receiver
            while i >= 0 and (text[i].isalnum() or text[i] in "_$"):
                i -= 1
        elif c.isalnum() or c in "_$":
            while i >= 0 and (text[i].isalnum() or text[i] in "_$"):
                i -= 1
        else:
            break
        # continue the chain across a dot (`a.b(...).c`), but not across `new`, casts or operators
        j = i
        while j >= 0 and text[j].isspace():
            j -= 1
        if j >= 0 and text[j] == "." and mask[j]:
            i = j - 1
            while i >= 0 and text[i].isspace():
                i -= 1
            continue
        return i + 1
    return i + 1


def receiver_calls(methods: tuple[str, ...], helper: str):
    """`recv.m(args)` -> `helper.m(recv, args)` for each m in `methods` (26.1 instance methods 1.21.1 lacks)."""
    rx = re.compile(r"\.\s*(" + "|".join(methods) + r")\s*\(")
    simple = helper.rsplit(".", 1)[-1]

    def fn(text: str, rel: str) -> str:
        pos = 0
        while True:
            mask = _code_mask(text)
            m = next((m for m in rx.finditer(text, pos) if mask[m.start()]), None)
            if m is None:
                return text
            start = _receiver_start(text, mask, m.start())
            receiver = text[start:m.start()].strip()
            if not receiver or receiver in (helper, simple, "super", "this") or receiver.endswith("." + simple):
                pos = m.end()
                continue
            rest = text[m.end():]
            sep = "" if rest.lstrip().startswith(")") else ", "
            text = f"{text[:start]}{helper}.{m.group(1)}({receiver}{sep}{rest}"
            pos = start + len(helper)

    return fn


# --- block entities: 26.1's saveAdditional(ValueOutput)/loadAdditional(ValueInput) -> 1.21.1's tag + registries ---

_TAG = "net.minecraft.nbt.CompoundTag portTag, net.minecraft.core.HolderLookup.Provider portRegistries"
_BLOCK_ENTITY_IO = [
    Regex(r"((?:public|protected)(?:\s+final)?\s+void\s+)saveAdditional\(\s*ValueOutput\s+(\w+)\s*\)\s*\{",
          lambda m: f"{m.group(1)}saveAdditional({_TAG}) {{\n        ValueOutput {m.group(2)} = "
                    f"{_SAVING}.TagValueOutput.wrap({_SAVING}.PortSaving.reporter(this), portRegistries, portTag);",
          files=OWN, note="1.21.1 BlockEntity.saveAdditional(CompoundTag, Provider)"),
    Regex(r"((?:public|protected)(?:\s+final)?\s+void\s+)loadAdditional\(\s*ValueInput\s+(\w+)\s*\)\s*\{",
          lambda m: f"{m.group(1)}loadAdditional({_TAG}) {{\n        ValueInput {m.group(2)} = "
                    f"{_SAVING}.TagValueInput.create({_SAVING}.PortSaving.reporter(this), portRegistries, portTag);",
          files=OWN, note="1.21.1 BlockEntity.loadAdditional(CompoundTag, Provider)"),
    Regex(r"super\.(saveAdditional|loadAdditional)\(\s*\w+\s*\)", r"super.\1(portTag, portRegistries)", files=OWN),
]

# 26.1's typed CompoundTag/ListTag getters, as static calls on the Nbt shim (ValueInput overloads cover the names both
# types have).
_NBT_GETTERS = ("getBooleanOr", "getByteOr", "getShortOr", "getIntOr", "getLongOr", "getFloatOr", "getDoubleOr",
                "getStringOr", "getCompoundOrEmpty", "getListOrEmpty")

# --- GameTests: 26.1's registry-based tests on 1.21.1's framework (shim/gametest, PortGameTests) ----------------------

_GAMETEST = f"{_SHIM}.gametest"
GAMETEST_FILES = tuple(p for p in OWN if "/src/gametest/" in p)
_PERF_BUDGET = ("lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/perf/PerfBudget.java",)
_ASSERT = "net.minecraft.gametest.framework.GameTestAssertException"


def drop_receiver(method: str, replacement: str):
    """`recv.method(args)` -> `replacement(args)`, for a 26.1 instance method that becomes a 1.21.1 constructor."""
    rx = re.compile(r"\.\s*" + method + r"\s*\(")

    def fn(text: str, rel: str) -> str:
        while True:
            mask = _code_mask(text)
            m = next((m for m in rx.finditer(text) if mask[m.start()]), None)
            if m is None:
                return text
            start = _receiver_start(text, mask, m.start())
            text = f"{text[:start]}{replacement}({text[m.end():]}"

    return fn


_GAMETESTS = [
    Regex(r"\bRegistries\.(TEST_FUNCTION|TEST_ENVIRONMENT_DEFINITION_TYPE)\b", rf"{_GAMETEST}.GameTestRegistries.\1",
          files=GAMETEST_FILES, note="1.21.1 has no test registries; Lib makes its own"),
    ImportMove("net.neoforged.neoforge.event.RegisterGameTestsEvent", f"{_GAMETEST}.RegisterGameTestsEvent",
               files=GAMETEST_FILES, note="26.1's event, posted by PortGameTests from 21.1's"),
    # 26.1's helper.assertionException(String or Component) is 1.21.1's new GameTestAssertException(String)
    Regex(r"\bassertionException\(\s*Component\.literal\(", "assertionException((", files=OWN),
    Transform(drop_receiver("assertionException", f"new {_ASSERT}"), name="assertionException -> new exception",
              files=OWN),
    # PerfBudget (neoforge main) can't see the gametest shims: 26.1's GameTestException is 1.21.1's assert exception
    ImportMove(f"{_GAMETEST}.GameTestException", _ASSERT, files=_PERF_BUDGET),
    Rename("GameTestException", "GameTestAssertException", files=_PERF_BUDGET),
    Regex(r"new GameTestAssertException\(Component\.literal\((.*)\), 0\)", r"new GameTestAssertException(\1)",
          scope="all", files=_PERF_BUDGET),
]


def _close_paren(text: str, mask: list[bool], open_: int) -> int:
    depth = 0
    for i in range(open_, len(text)):
        if mask[i] and text[i] == "(":
            depth += 1
        elif mask[i] and text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def class_arg_call(method: str, helper: str):
    """`recv.method(args, X.class)` -> `helper.method(recv, args, X.class)`; calls without a class argument stay."""
    rx = re.compile(r"\.\s*" + method + r"\s*\(")

    def fn(text: str, rel: str) -> str:
        pos = 0
        while True:
            mask = _code_mask(text)
            m = next((m for m in rx.finditer(text, pos) if mask[m.start()]), None)
            if m is None:
                return text
            close = _close_paren(text, mask, m.end() - 1)
            if not re.search(r",\s*[\w.]+\.class\s*$", text[m.end():close]):
                pos = m.end()
                continue
            start = _receiver_start(text, mask, m.start())
            text = f"{text[:start]}{helper}.{method}({text[start:m.start()].strip()}, {text[m.end():]}"
            pos = start + len(helper) + len(method) + 2

    return fn


def drop_call(method: str):
    """Removes `.method(args)` from a call chain (26.1 builder calls 1.21.1 doesn't need, like Properties.setId)."""
    rx = re.compile(r"\.\s*" + method + r"\s*\(")

    def fn(text: str, rel: str) -> str:
        while True:
            mask = _code_mask(text)
            m = next((m for m in rx.finditer(text) if mask[m.start()]), None)
            if m is None:
                return text
            text = text[:m.start()] + text[_close_paren(text, mask, m.end() - 1) + 1:]

    return fn


def append_arg(call: str, arg: str):
    """`call(args)` -> `call(args, arg)` (a constructor 1.21.1 gives one more parameter)."""
    rx = re.compile(re.escape(call) + r"\s*\(")

    def fn(text: str, rel: str) -> str:
        pos = 0
        while True:
            mask = _code_mask(text)
            m = next((m for m in rx.finditer(text, pos) if mask[m.start()]), None)
            if m is None:
                return text
            close = _close_paren(text, mask, m.end() - 1)
            text = f"{text[:close]}, {arg}{text[close:]}"
            pos = close

    return fn


_SKIP_SIDE_EFFECTS = r"(\w+)\.setBlock\(([^;]*?),\s*Block\.UPDATE_CLIENTS \| Block\.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS\);"
_MACHINE_RECIPE_TESTS = tuple(p for p in GAMETEST_FILES if p.endswith("/MachineRecipeGameTests.java"))
_MACHINE_PROCESS_TESTS = tuple(p for p in GAMETEST_FILES if p.endswith("/MachineProcessGameTests.java"))
_PIPE_SHOWCASE = tuple(p for p in GAMETEST_FILES if "/PipeShowcase" in p)

# 26.1 game and NeoForge apis the GameTests use, in their 1.21.1 shape (only in saving's GameTest files).
_GAMETEST_API = [
    Transform(class_arg_call("getBlockEntity", f"{_GAMETEST}.GameTestHelpers"),
              name="helper.getBlockEntity(pos, type) -> GameTestHelpers", files=GAMETEST_FILES),
    # Block/Item.Properties.setId: 1.21.1 takes the id from the registry
    Transform(drop_call("setId"), name="drop Properties.setId", files=GAMETEST_FILES),
    # BlockEntityType(factory, blocks) -> 1.21.1's (factory, blocks, dataFixerType)
    Transform(append_arg("new BlockEntityType<>", "null"), name="BlockEntityType data fixer type",
              files=GAMETEST_FILES),
    # neighborChanged(..., @Nullable Orientation, boolean) -> 1.21.1's (..., BlockPos fromPos, boolean)
    Regex(r"@Nullable Orientation (\w+)", r"BlockPos \1", files=GAMETEST_FILES),
    Regex(r"import net\.minecraft\.world\.level\.redstone\.Orientation;\n", "", scope="all", files=GAMETEST_FILES),
    # Commands.hasPermission(level) predicate
    Regex(r"Commands\.hasPermission\(([^()]*)\)", r"source -> source.hasPermission(\1)", files=GAMETEST_FILES),
    # Registry.getValueOrThrow(key) is getOrThrow(key) on 1.21.1
    Rename("getValueOrThrow", "getOrThrow", files=GAMETEST_FILES),
    Regex(r"\.byKey\(\s*ResourceKey\.(?:<Recipe<\?>>)?create\(\s*Registries\.RECIPE,\s*([^;]*?)\)\)", r".byKey(\1)",
          files=GAMETEST_FILES),
    Regex(r"\.id\(\)\.location\(\)", ".id()", files=_MACHINE_RECIPE_TESTS + _MACHINE_PROCESS_TESTS),
    # MachineProcessGameTests' reload pack: 1.21.1's pack.mcmeta has one pack_format, and a recipe's item ingredient
    # is an {"item": ...} object
    Regex(r'\\"min_format\\": "\s*\+ SharedConstants\.DATA_PACK_FORMAT_MAJOR \+ ", \\"max_format\\": "\s*'
          r'\+ SharedConstants\.DATA_PACK_FORMAT_MAJOR \+ "',
          r'\\"pack_format\\": " + SharedConstants.DATA_PACK_FORMAT + "', scope="all", files=_MACHINE_PROCESS_TESTS),
    Regex(r'\{\\"ingredient\\": "\s*\+ "\\"(minecraft:\w+)\\"\}', r'{\\"ingredient\\": {\\"item\\": \\"\1\\"}}',
          scope="all", files=_MACHINE_PROCESS_TESTS),
    # LevelChunk.tryMarkSaved() (clears the unsaved flag) is setUnsaved(false)
    Regex(r"\.tryMarkSaved\(\)", ".setUnsaved(false)", files=GAMETEST_FILES),
    # BlockEntity.loadWithComponents(ValueInput) is (CompoundTag, Provider)
    Regex(r"\.loadWithComponents\(\s*TagValueInput\.create\(\s*ProblemReporter\.DISCARDING,\s*([^;]*?),\s*(\w+)\)\)",
          r".loadWithComponents(\2, \1)", files=GAMETEST_FILES),
    # CompoundTag.keySet() is getAllKeys()
    Regex(r"(savedContents\([^()]*\))\.keySet\(\)", r"\1.getAllKeys()", files=GAMETEST_FILES),
    # Level.getMaxY() is the highest buildable y, one below 1.21.1's getMaxBuildHeight()
    Regex(r"\b(\w+)\.getMaxY\(\)", r"(\1.getMaxBuildHeight() - 1)", files=GAMETEST_FILES),
    # PipeShowcase: entity tags, spawn reasons, chunk positions, side-effect-free block removal
    Regex(r"\.entityTags\(\)", ".getTags()", files=_PIPE_SHOWCASE),
    Regex(r"EntitySpawnReason\.COMMAND,\s*", "", files=_PIPE_SHOWCASE),
    Regex(r"import net\.minecraft\.world\.entity\.EntitySpawnReason;\n", "", scope="all", files=_PIPE_SHOWCASE),
    Regex(r"\bchunk\.(x|z)\(\)", r"chunk.\1", files=_PIPE_SHOWCASE),
    Regex(r"\bchunk\.pack\(\)", "chunk.toLong()", files=_PIPE_SHOWCASE),
    Regex(_SKIP_SIDE_EFFECTS,
          rf"{_SAVING}.PortSaving.withoutSideEffects(() -> \1.setBlock(\2, Block.UPDATE_CLIENTS));",
          files=_PIPE_SHOWCASE),
    Regex(r"\.snapTo\(", ".moveTo(", files=_PIPE_SHOWCASE),
    # recipe ids: ResourceKey<Recipe<?>> in 26.1, ResourceLocation in 1.21.1; assemble takes the registries
    Regex(r"private static ResourceKey<Recipe<\?>> recipe\(String path\) \{\s*"
          r"return ResourceKey\.create\(Registries\.RECIPE, MaxtechLib\.id\(path\)\);",
          "private static net.minecraft.resources.ResourceLocation recipe(String path) {\n        return MaxtechLib.id(path);",
          files=tuple(p for p in GAMETEST_FILES if p.endswith("/ContentToggleGameTests.java"))),
    # other's shim of 26.1's ItemStackTemplate, which Lib's ItemOutput takes
    ImportMove("net.minecraft.world.item.ItemStackTemplate", f"{_SHIM}.misc.ItemStackTemplate",
               files=_MACHINE_RECIPE_TESTS),
    Regex(r"Ingredient\.of\(items\.getOrThrow\(([\w.]+)\)\)", r"Ingredient.of(\1)", files=_MACHINE_RECIPE_TESTS),
    Regex(r"DataComponentIngredient\.of\((\w+),\s*DataComponentPatch\.builder\(\)\.set\(([^;]*?)\)\s*\.build\(\),\s*",
          r"DataComponentIngredient.of(\1, \2, ", scope="all", files=_MACHINE_RECIPE_TESTS),
    Regex(r"public void accept\(ResourceKey<Recipe<\?>> (\w+),", r"public void accept(ResourceLocation \1,",
          files=_MACHINE_RECIPE_TESTS),
    # 1.21.1's RecipeOutput has no root advancement step
    Regex(r"@Override\s+public void includeRootAdvancement\(\)", "public void includeRootAdvancement()",
          files=_MACHINE_RECIPE_TESTS),
    Regex(r"\.assemble\(input\)", ".assemble(input, level.registryAccess())",
          files=tuple(p for p in GAMETEST_FILES if p.endswith("/FacadeRecipeGameTests.java"))),
    # 26.1's permission sets are 1.21.1's permission levels (gamemaster = 2)
    Regex(r"\.permissions\(\)\.hasPermission\(Permissions\.COMMANDS_GAMEMASTER\)", ".hasPermissions(2)",
          files=GAMETEST_FILES),
    Regex(r"public PermissionSet permissions\(\) \{\s*return LevelBasedPermissionSet\.GAMEMASTER;",
          "public int getPermissionLevel() {\n                return 2;", files=GAMETEST_FILES),
    Regex(r"import net\.minecraft\.server\.permissions\.\w+;\n", "", scope="all", files=GAMETEST_FILES),
    # the block atlas: 26.1's atlas manager, 1.21.1's texture atlas getter
    Regex(r"Minecraft\.getInstance\(\)\.getAtlasManager\(\)\.getAtlasOrThrow\(AtlasIds\.BLOCKS\)\.getSprite\((\w+)\)",
          r"Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(\1)",
          files=GAMETEST_FILES),
    Regex(r"import net\.minecraft\.data\.AtlasIds;\n", "", scope="all", files=GAMETEST_FILES),
]

_CONDUIT_BE = ("lib/common/src/main/java/dev/silencebound/maxtech/lib/conduit/ConduitBlockEntity.java",)

_PRE_REMOVE_RX = re.compile(r"@Override\s+public void preRemoveSideEffects\(BlockPos (\w+), BlockState (\w+)\) \{"
                            r"(\s*super\.preRemoveSideEffects\(\w+, \w+\);)?")
_SET_REMOVED_RX = re.compile(r"public void setRemoved\(\) \{")


def _class_body(text: str, mask: list[bool], pos: int) -> tuple[int, int]:
    """The [open brace, close brace] of the class body around `pos` (the innermost brace pair that holds a method)."""
    depth, i = 0, pos
    while i >= 0:  # the method's class body opens at the first unmatched `{` before pos
        if mask[i] and text[i] == "}":
            depth += 1
        elif mask[i] and text[i] == "{":
            if depth == 0:
                break
            depth -= 1
        i -= 1
    start, depth, j = i, 0, i
    while j < len(text):
        if mask[j] and text[j] == "{":
            depth += 1
        elif mask[j] and text[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return start, j


def pre_remove_side_effects(text: str, rel: str) -> str:
    """26.1's BlockEntity.preRemoveSideEffects runs when the block is broken or replaced, not on chunk unload. 1.21.1
    has no such hook, but calls onChunkUnloaded before setRemoved on unload, so setRemoved runs the side effects unless
    the chunk is unloading, first (26.1 runs them while the block entity is still in the level)."""
    hook = (f"        if (!portUnloading && !isRemoved() && !{_SAVING}.PortSaving.skippingSideEffects()) {{\n"
            "            preRemoveSideEffects(worldPosition, getBlockState());\n        }\n")
    while True:
        mask = _code_mask(text)
        m = next((m for m in _PRE_REMOVE_RX.finditer(text) if mask[m.start()]), None)
        if m is None:
            return text
        start, end = _class_body(text, mask, m.start())
        body = text[start:end]
        existing = _SET_REMOVED_RX.search(body)
        method = f"/** Port: run by setRemoved. */\n    public void preRemoveSideEffects(BlockPos {m.group(1)}, BlockState {m.group(2)}) {{"
        added = ("private boolean portUnloading;\n\n    @Override\n    public void onChunkUnloaded() {\n"
                 "        portUnloading = true;\n        super.onChunkUnloaded();\n    }\n\n    ")
        if existing is None:
            added += f"@Override\n    public void setRemoved() {{\n{hook}        super.setRemoved();\n    }}\n\n    "
        text = text[:m.start()] + added + method + text[m.end():]
        if existing is not None:
            at = start + existing.end()
            at += len(added) + len(method) - (m.end() - m.start()) if start + existing.start() > m.start() else 0
            text = text[:at] + "\n" + hook.rstrip("\n") + text[at:]


_PRE_REMOVE = [
    Transform(pre_remove_side_effects, name="preRemoveSideEffects -> setRemoved unless unloading", files=OWN),
    Regex(r"(?<![\w.])problemPath\(\)", f"{_SAVING}.PortSaving.problemPath(this)", files=_CONDUIT_BE),
]

RULES = [
    # 26.1's ProblemReporter (path elements, Problem records, DISCARDING); only saving's files use it.
    ImportMove("net.minecraft.util.ProblemReporter", f"{_SAVING}.ProblemReporter", files=OWN),
    *_BLOCK_ENTITY_IO,
    *_PRE_REMOVE,
    *_GAMETESTS,
    *_GAMETEST_API,
    Transform(receiver_calls(_NBT_GETTERS, f"{_SAVING}.Nbt"), name="26.1 CompoundTag getters -> Nbt shim", files=OWN),
    # 26.1's CompoundTag.getString returns an Optional; only where every getString call is on a CompoundTag
    Transform(receiver_calls(("getString",), f"{_SAVING}.Nbt"), name="CompoundTag.getString -> Nbt shim",
              files=("lib/common/src/test/java/dev/silencebound/maxtech/lib/save/SaveFormatTest.java",)),
    # 1.21.1's ListTag holds one element type; NbtOps saves a mixed list as {"": value} wrappers, so the test builds
    # its saved form through NbtOps too.
    Regex(r"ListTag keys = new ListTag\(\);\s*keys\.add\(IntTag\.valueOf\(7\)\);\s*"
          r"keys\.add\(StringTag\.valueOf\(\"not a number\"\)\);\s*saved\.put\(\"keys\", keys\);",
          "saved.put(\"keys\", NbtOps.INSTANCE.createList(\n"
          "                java.util.stream.Stream.of(IntTag.valueOf(7), StringTag.valueOf(\"not a number\"))));",
          scope="all",
          files=("lib/common/src/test/java/dev/silencebound/maxtech/lib/api/transfer/PortFilterTest.java",)),
]
