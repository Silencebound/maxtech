"""Rules of work package `transfer`: the transfer bridge: the vendored api (port/shim/.../shim/transfer/**) and Lib code on it (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.
"""

from framework import ImportMove, Regex, Rename, Transform  # noqa: F401

RULES = []
