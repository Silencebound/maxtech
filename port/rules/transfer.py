"""Rules of work package `transfer`: the transfer bridge: the vendored api (port/shim/.../shim/transfer/**) and Lib code on it (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.
"""

from framework import ImportMove, Regex, Rename, Transform  # noqa: F401

_FE_LOOKUPS = ("lib/common/src/main/java/dev/silencebound/maxtech/lib/energy/FeLookups.java",)

RULES = [
    # 1.21.1's neoforge namespace has no energy_handler capability; the vendored one (shim bridge.Capabilities) is
    # maxtech_lib:energy_handler, and FeLookups gets it by name (create-or-get).
    Regex(r'"neoforge", "energy_handler"', '"maxtech_lib", "energy_handler"', scope="all", files=_FE_LOOKUPS),
    Regex(r"\{@code neoforge:energy_handler\}", "{@code maxtech_lib:energy_handler}", scope="all",
          files=_FE_LOOKUPS),
]

_BRIDGE = ("lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/transfer/ResourceHandlerBridge.java",)

RULES += [
    # 1.21.1 handlers behind the Legacy* wrappers have no transactions: they reach Lib's ports as non-transactional
    # KindHandlers, so KindMoves moves through them foreign side last instead of trusting the deferred ledger.
    Regex(r"Optional\.of\(new Wrapped\(current\)\)",
          "Optional.of(dev.silencebound.maxtech.lib.shim.transfer.bridge.ForeignKinds.wrap(current, "
          "new Wrapped(current), toResource))", files=_BRIDGE),
    Regex(r"which\n \* always take part in transactions\.",
          "which\n * take part in transactions, except 1.21.1 handlers ({@code ForeignKinds}).", scope="all",
          files=_BRIDGE),
]
