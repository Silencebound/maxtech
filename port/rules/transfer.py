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
