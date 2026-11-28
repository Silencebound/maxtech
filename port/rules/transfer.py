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

_ITEM_CHANNEL = ("lib/common/src/main/java/dev/silencebound/maxtech/lib/transfer/ItemChannel.java",)

_ITEM_EDITS = [
    # imports
    ("import dev.silencebound.maxtech.lib.resource.StackKeys;\n",
     "import dev.silencebound.maxtech.lib.resource.StackKeys;\n"
     "import dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyDirect;\n"
     "import dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyForeignLast;\n"),
    # push: a 1.21.1 target is moved into foreign side last, then the root commits
    ("""        int moved = 0;
        try (Transaction root = Transaction.openRoot()) {
            for (int b = 0; b < buffers.length && moved < amount; b++) {
                if (!pushes[b]) {
                    continue;
                }
                RoleView view = buffers[b];
                for (int slot = 0; slot < view.size() && moved < amount; slot++) {
                    ItemResource resource = pushable(view, slot, port);
                    if (resource == null) {
                        continue;
                    }
                    int offer = (int) Math.min(amount - moved, view.getAmountAsLong(slot));
""",
     """        int moved = 0;
        LegacyDirect<ItemResource> foreign = port == null ? LegacyDirect.of(target) : null;
        boolean foreignMoved = false;
        try (Transaction root = Transaction.openRoot()) {
            for (int b = 0; b < buffers.length && moved < amount && !foreignMoved; b++) {
                if (!pushes[b]) {
                    continue;
                }
                RoleView view = buffers[b];
                for (int slot = 0; slot < view.size() && moved < amount && !foreignMoved; slot++) {
                    ItemResource resource = pushable(view, slot, port);
                    if (resource == null) {
                        continue;
                    }
                    int offer = (int) Math.min(amount - moved, view.getAmountAsLong(slot));
                    if (foreign != null) {
                        // 1.21.1: the target has no transactions; it changes last, and then the root commits.
                        pushSide.view = view;
                        pushSide.slot = slot;
                        int step = LegacyForeignLast.into(pushSide, foreign, resource, offer, root);
                        pushSide.view = null;
                        moved += step;
                        foreignMoved = step > 0;
                        continue;
                    }
"""),
    # pull: a 1.21.1 source is taken from foreign side last, then the root commits
    ("""        int moved = 0;
        try (Transaction root = Transaction.openRoot()) {
            for (int index = 0; index < size && moved < amount; index++) {
""",
     """        int moved = 0;
        LegacyDirect<ItemResource> foreign = LegacyDirect.of(source);
        try (Transaction root = Transaction.openRoot()) {
            for (int index = 0; index < size && moved < amount; index++) {
"""),
    ("""                try (Transaction part = Transaction.open(root)) {
                    int put = insertPull(resource, offer, part);
""",
     """                if (foreign != null) {
                    // 1.21.1: the source has no transactions; it changes last, and then the root commits.
                    int step = LegacyForeignLast.from(foreign, pullSide, resource, offer, root);
                    moved += step;
                    if (step > 0) {
                        break;
                    }
                    continue;
                }
                try (Transaction part = Transaction.open(root)) {
                    int put = insertPull(resource, offer, part);
"""),
    ("""    /** Inserts into the pulling buffers, first-fit in the order they were added; returns what went in. */
""",
     """    /** 1.21.1: Lib's side of a push into a 1.21.1 handler, one pushing slot; reused, set per move. */
    private static final class PushSide implements LegacyForeignLast.LibSide<ItemResource> {
        @Nullable RoleView view;
        int slot;

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            RoleView current = view;
            return current == null ? 0 : current.extract(slot, resource, amount, transaction);
        }
    }

    /** 1.21.1: Lib's side of a pull from a 1.21.1 handler, the pulling buffers. */
    private final class PullSide implements LegacyForeignLast.LibSide<ItemResource> {
        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return insertPull(resource, amount, transaction);
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }
    }

    private final PushSide pushSide = new PushSide();
    private final PullSide pullSide = new PullSide();

    /** Inserts into the pulling buffers, first-fit in the order they were added; returns what went in. */
"""),
]


def _item_channel(text: str, rel: str) -> str:
    for old, new in _ITEM_EDITS:
        if text.count(old) != 1:
            raise SystemExit(f"transfer.py: ItemChannel changed on main; re-check the foreign-last edit at:\n{old}")
        text = text.replace(old, new)
    return text


RULES += [
    # 1.21.1 item handlers have no transactions: ItemChannel moves into and out of them foreign side last
    # (LegacyForeignLast), like KindMoves, instead of trusting the deferred ledger (owner, 2026-11-28).
    Transform(_item_channel, name="ItemChannel foreign-last", files=_ITEM_CHANNEL),
]
