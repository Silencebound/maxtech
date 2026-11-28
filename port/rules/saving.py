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

_CONDUIT_BE = ("lib/common/src/main/java/dev/silencebound/maxtech/lib/conduit/ConduitBlockEntity.java",)
# 26.1's BlockEntity.preRemoveSideEffects runs when the block is broken or replaced, not on chunk unload. 1.21.1 has no
# such hook, but it calls onChunkUnloaded before setRemoved on unload, so setRemoved runs the side effects unless the
# chunk is unloading (before super.setRemoved, as 26.1 calls them while the block entity is still in the level).
_PRE_REMOVE = [
    Regex(r"@Override\s+public void preRemoveSideEffects\(BlockPos pos, BlockState state\) \{\s*"
          r"super\.preRemoveSideEffects\(pos, state\);",
          "private void preRemoveSideEffects(BlockPos pos, BlockState state) {", files=_CONDUIT_BE),
    Regex(r"@Override\s+public void setRemoved\(\) \{",
          "private boolean portUnloading;\n\n    @Override\n    public void onChunkUnloaded() {\n"
          "        portUnloading = true;\n        super.onChunkUnloaded();\n    }\n\n"
          "    @Override\n    public void setRemoved() {\n"
          "        if (!portUnloading && !isRemoved()) {\n            preRemoveSideEffects(worldPosition, getBlockState());\n"
          "        }", files=_CONDUIT_BE),
    Regex(r"(?<![\w.])problemPath\(\)", f"{_SAVING}.PortSaving.problemPath(this)", files=_CONDUIT_BE),
    # NeoForge 21.1's model data lives below client.model
    ImportMove("net.neoforged.neoforge.model.data", "net.neoforged.neoforge.client.model.data", files=_CONDUIT_BE),
]

RULES = [
    # 26.1's ProblemReporter (path elements, Problem records, DISCARDING); only saving's files use it.
    ImportMove("net.minecraft.util.ProblemReporter", f"{_SAVING}.ProblemReporter", files=OWN),
    *_BLOCK_ENTITY_IO,
    *_PRE_REMOVE,
    Transform(receiver_calls(_NBT_GETTERS, f"{_SAVING}.Nbt"), name="26.1 CompoundTag getters -> Nbt shim", files=OWN),
]
