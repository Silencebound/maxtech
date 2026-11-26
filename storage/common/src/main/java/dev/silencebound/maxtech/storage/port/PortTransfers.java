package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.network.OperationMoves;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
// PLATFORM-LEAK: every port move is a NeoForge transfer transaction against the real handlers (PRD 7.2).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One port operation between an inventory and the network, in one root transaction against the real handlers (PRD 7.2,
 * Performance: the cache is a view). The inventory side is simulated first, then the network operation runs for real,
 * then the inventory side runs for exactly what the network took or gave; if the inventory then moves a different
 * amount, everything rolls back. So the network's counts change only by amounts that really moved, through its
 * providers' listeners, and an inventory emptied or filled behind the port's back moves nothing. Server thread.
 */
final class PortTransfers {
    private PortTransfers() {
    }

    /**
     * Moves up to {@code amount} of {@code resource} out of index {@code index} of {@code source} into the network and
     * returns how much moved; 0 when nothing could, the network refused the operation, or the source broke its promise.
     * Must be called outside any transaction.
     */
    static <T extends Resource> long pull(ResourceHandler<T> source, int index, T resource, int amount,
            StorageTransfer transfer) {
        try (Transaction root = Transaction.openRoot()) {
            int available;
            try (Transaction simulation = Transaction.open(root)) {
                available = source.extract(index, resource, amount, simulation);
            }
            if (available <= 0) {
                return 0;
            }
            StackKey key = StackKeys.of(resource);
            long stored = transfer.insert(key, available, root);
            if (stored <= 0) {
                return 0;
            }
            int taken = source.extract(index, resource, (int) stored, root);
            if (taken != stored) {
                return 0;
            }
            root.commit();
            transfer.network().transferred(key, stored);
            return stored;
        }
    }

    /**
     * One import operation over several slots (M7E decision 4): takes up to {@code amount} in all from the indices of
     * {@code source} that {@code filter} accepts, starting at {@code start} and wrapping around, whatever their kinds,
     * and pays for it as one operation. Each index moves in its own nested transaction (simulated, stored, then taken
     * for exactly what was stored), so an index that breaks its promise rolls back alone; the operation's energy is
     * paid at the end in the root transaction, and if that fails everything rolls back. Records what moved and from
     * where in {@code moves}; returns the total, 0 when nothing moved or the network refused. When no index holds
     * anything the filter accepts it returns 0 without opening an operation or a transaction. Must be called outside
     * any transaction.
     */
    // A resource is skipped by identity when it is the one the filter last refused: equal by identity means equal, and
    // the handlers hand out one shared resource for a plain stack, so a slot full of refused stacks is checked once.
    @SuppressWarnings("ReferenceEquality")
    static <T extends Resource> long pullMany(ResourceHandler<T> source, int start, PortFilter filter, int amount,
            StackKey.Kind kind, StorageTransfer transfer, OperationMoves moves) {
        moves.clear();
        if (amount <= 0) {
            return 0;
        }
        int size = source.size();
        int first = start < size ? start : 0;
        // A read-only look for the first index worth a move: an inventory that is empty or holds only what the filter
        // refuses costs one walk, without an operation or a root transaction (whose open walks the stack).
        @Nullable
        T refused = null;
        int from = 0;
        for (; from < size; from++) {
            int index = first + from < size ? first + from : first + from - size;
            T resource = source.getResource(index);
            if (resource.isEmpty() || resource == refused || source.getAmountAsLong(index) <= 0) {
                continue;
            }
            if (filter.accepts(resource)) {
                break;
            }
            refused = resource;
        }
        if (from == size) {
            return 0;
        }
        long budget = Math.min(amount, transfer.beginOperation(kind));
        if (budget <= 0) {
            return 0;
        }
        try (Transaction root = Transaction.openRoot()) {
            long moved = 0;
            boolean paid;
            try {
                for (int n = from; n < size && moved < budget; n++) {
                    int index = first + n < size ? first + n : first + n - size;
                    T resource = source.getResource(index);
                    if (resource.isEmpty() || resource == refused || source.getAmountAsLong(index) <= 0
                            || !filter.accepts(resource)) {
                        continue;
                    }
                    StackKey key = StackKeys.of(resource);
                    long part = pullPart(source, index, resource, key, (int) (budget - moved), transfer, root);
                    if (part > 0) {
                        moved += part;
                        moves.add(key, part, index);
                    }
                }
            } finally {
                paid = transfer.finishOperation(kind, moved, root);
            }
            if (!paid) {
                moves.clear();
                return 0;
            }
            root.commit();
            moves.reportTo(transfer.network());
            return moved;
        }
    }

    /** One index's share of {@link #pullMany} in a transaction nested in {@code root}; what it kept. */
    private static <T extends Resource> long pullPart(ResourceHandler<T> source, int index, T resource, StackKey key,
            int amount, StorageTransfer transfer, Transaction root) {
        try (Transaction part = Transaction.open(root)) {
            int available;
            try (Transaction simulation = Transaction.open(part)) {
                available = source.extract(index, resource, amount, simulation);
            }
            if (available <= 0) {
                return 0;
            }
            long stored = transfer.insert(key, available, part);
            if (stored <= 0) {
                return 0;
            }
            int taken = source.extract(index, resource, (int) stored, part);
            if (taken != stored) {
                return 0;
            }
            part.commit();
            return stored;
        }
    }

    /**
     * One export operation over several keys (M7E follow-up): fills {@code target} with up to {@code amount} in all of
     * the keys in {@code list} the network holds, starting at {@code start} and wrapping around, and pays for it as one
     * operation. A key moves at most what its entry's {@code room} still allows (the regulator, B09 item 22; the room
     * shrinks by what moved), and at most {@code maxTries} keys that the network holds and that {@link #mayFit} are
     * tried; keys that cannot fit do not use up that cap, but at most {@link #LOOKS_PER_TRY} times as many keys are
     * looked at, so a long list bounds the work. Each key moves in its own nested transaction (room simulated, taken
     * from the network, then put for exactly what was taken), so a target that takes less than it said rolls back that
     * key alone; the energy is paid at the end in the root transaction, and if that fails everything rolls back.
     * Records what moved in {@code moves}, with the list index as the index; returns the total, 0 when nothing moved or
     * the network refused. Must be called outside any transaction.
     */
    @SuppressWarnings("unchecked") // the list of a kind holds resources of the target's kind only
    static <T extends Resource> long pushMany(ResourceHandler<T> target, PortExports list, int start, long[] room,
            int maxTries, int amount, StackKey.Kind kind, StorageTransfer transfer, OperationMoves moves) {
        moves.clear();
        int size = list.size();
        if (amount <= 0 || size == 0) {
            return 0;
        }
        long budget = Math.min(amount, transfer.beginOperation(kind));
        if (budget <= 0) {
            return 0;
        }
        try (Transaction root = Transaction.openRoot()) {
            long moved = 0;
            boolean paid;
            try {
                int first = start >= 0 && start < size ? start : 0;
                int tries = 0;
                int looks = 0;
                int maxLooks = looksFor(maxTries);
                for (int n = 0; n < size && moved < budget && tries < maxTries && looks < maxLooks; n++) {
                    int i = first + n < size ? first + n : first + n - size;
                    int entry = list.entry(i);
                    long allowed = Math.min(budget - moved, room[entry]);
                    if (allowed <= 0) {
                        continue;
                    }
                    StackKey key = list.key(i);
                    if (transfer.network().contents().amount(key) <= 0) {
                        continue;
                    }
                    looks++;
                    if (!mayFit(target, (T) list.resource(i))) {
                        continue;
                    }
                    tries++;
                    long part = pushPart(target, key, (T) list.resource(i), (int) Math.min(allowed, Integer.MAX_VALUE),
                            transfer, root);
                    if (part > 0) {
                        moved += part;
                        room[entry] -= part;
                        moves.add(key, part, i);
                    }
                }
            } finally {
                paid = transfer.finishOperation(kind, moved, root);
            }
            if (!paid) {
                moves.clear();
                return 0;
            }
            root.commit();
            moves.reportTo(transfer.network());
            return moved;
        }
    }

    /** How many more keys an operation looks at ({@link #mayFit}) than it tries to move. */
    static final int LOOKS_PER_TRY = 16;

    /** The most keys an operation with a cap of {@code maxTries} looks at. */
    static int looksFor(int maxTries) {
        return (int) Math.min(Integer.MAX_VALUE, (long) maxTries * LOOKS_PER_TRY);
    }

    /**
     * A read-only first look whether {@code target} has room for anything: an empty index, or one below its capacity
     * (or of unknown capacity). An export into a full inventory stops here, after one walk over its indices.
     */
    static <T extends Resource> boolean anyRoom(ResourceHandler<T> target) {
        int size = target.size();
        for (int index = 0; index < size; index++) {
            T held = target.getResource(index);
            if (held.isEmpty()) {
                return true;
            }
            long capacity = target.getCapacityAsLong(index, held);
            if (capacity <= 0 || target.getAmountAsLong(index) < capacity) {
                return true;
            }
        }
        return false;
    }

    /**
     * A read-only first look whether {@code target} may take any of {@code resource}: an empty index, or one holding it
     * below its capacity, or one whose capacity it does not know (0 or less). No transaction, so a full inventory costs
     * an export with fuzzy, tag or mod entries one walk over its indices per key instead of two transactions.
     */
    static <T extends Resource> boolean mayFit(ResourceHandler<T> target, T resource) {
        int size = target.size();
        for (int index = 0; index < size; index++) {
            T held = target.getResource(index);
            if (held.isEmpty()) {
                return true;
            }
            if (held.equals(resource)) {
                long capacity = target.getCapacityAsLong(index, resource);
                if (capacity <= 0 || target.getAmountAsLong(index) < capacity) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One key's share of {@link #pushMany} in a transaction nested in {@code root}; what it put. */
    private static <T extends Resource> long pushPart(ResourceHandler<T> target, StackKey key, T resource, int amount,
            StorageTransfer transfer, Transaction root) {
        try (Transaction part = Transaction.open(root)) {
            int room;
            try (Transaction simulation = Transaction.open(part)) {
                room = insertStacking(target, resource, amount, simulation);
            }
            if (room <= 0) {
                return 0;
            }
            long taken = transfer.extract(key, room, part);
            if (taken <= 0) {
                return 0;
            }
            int put = insertStacking(target, resource, (int) taken, part);
            if (put != taken) {
                return 0;
            }
            part.commit();
            return taken;
        }
    }

    /**
     * Moves up to {@code amount} of {@code key} (as {@code resource}) out of the network into {@code target}, filling
     * matching indices first, and returns how much moved; 0 when the target has no room, the network holds none or
     * refused the operation, or the target took less than it said. Must be called outside any transaction.
     */
    static <T extends Resource> long push(ResourceHandler<T> target, StackKey key, T resource, int amount,
            StorageTransfer transfer) {
        try (Transaction root = Transaction.openRoot()) {
            int room;
            try (Transaction simulation = Transaction.open(root)) {
                room = insertStacking(target, resource, amount, simulation);
            }
            if (room <= 0) {
                return 0;
            }
            long taken = transfer.extract(key, room, root);
            if (taken <= 0) {
                return 0;
            }
            int put = insertStacking(target, resource, (int) taken, root);
            if (put != taken) {
                return 0;
            }
            root.commit();
            transfer.network().transferred(key, taken);
            return taken;
        }
    }

    /**
     * {@code ResourceHandlerUtil.insertStacking} without its own nested transaction: indices holding something first,
     * then empty ones, all in {@code transaction}. The port opens a transaction around each call already, and every
     * {@code Transaction.open} walks the stack for its caller, which made up a good part of an export's time (M7E item
     * D).
     */
    static <T extends Resource> int insertStacking(ResourceHandler<T> handler, T resource, int amount,
            TransactionContext transaction) {
        int inserted = 0;
        int size = handler.size();
        for (int index = 0; index < size && inserted < amount; index++) {
            if (!handler.getResource(index).isEmpty()) {
                inserted += handler.insert(index, resource, amount - inserted, transaction);
            }
        }
        for (int index = 0; index < size && inserted < amount; index++) {
            if (handler.getResource(index).isEmpty()) {
                inserted += handler.insert(index, resource, amount - inserted, transaction);
            }
        }
        return inserted;
    }
}
