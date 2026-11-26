package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
// PLATFORM-LEAK: every move of a cell transfer is one NeoForge transfer transaction (PRD 7.2, never dupe or lose).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * A drive's cell transfer (M7C item 12): per slot, empty its cell into the network or fill it from the network, to
 * empty a cell before rebuilding it or to rebalance drives. A player starts and stops it from the drive's screen.
 *
 * <p>
 * The work is one {@link StorageTask} of the drive on its network's scheduler, asleep while no slot transfers. Each run
 * makes at most {@code cells.transferOperations} operations over all slots, each one batch of one kind
 * ({@link StorageTransfer#batchLimit}) through the drive's own transfer, so it pays energy like a port and waits when
 * the network refuses for lack of it. Every operation is one root transaction: the network side runs first, then the
 * cell side for exactly that amount, and both roll back unless they match.
 *
 * <ul>
 * <li>Emptying: the cell is {@linkplain CellInventory#setDraining draining} for the whole transfer, so neither the
 * transfer's inserts nor anyone else's go back into it. It ends {@link #FINISHED} once the cell is empty, or
 * {@link #BLOCKED} after a pass over the cell's kinds that moved nothing (the network has no room for the rest).</li>
 * <li>Filling: the cell holds its extracts only around the transfer's own network extract, so that takes from the other
 * providers. A pass goes over the network's kinds as they were when it started; the cell's own rules (type, bytes,
 * partition, bulk) decide what it takes, so a partitioned cell takes only what its filter allows. It ends
 * {@link #FINISHED} once the cell is full, or {@link #BLOCKED} after a pass that moved nothing.</li>
 * </ul>
 *
 * The mode per slot is saved with the drive, so a transfer goes on after a chunk unload or a restart; passes and
 * results are not saved. Taking the cell out stops its slot's transfer. Server thread only.
 */
public final class CellTransfer {
    /** No transfer in this slot. */
    public static final int NONE = 0;
    /** The slot's cell empties into the network. */
    public static final int EMPTY = 1;
    /** The slot's cell fills from the network. */
    public static final int FILL = 2;
    /** Result of a transfer that ended because the cell is empty (emptying) or full (filling). */
    public static final int FINISHED = 1;
    /**
     * Result of a transfer that ended because a whole pass moved nothing: no room in the network, or nothing to take.
     */
    public static final int BLOCKED = 2;
    private static final int BITS = 2;
    private static final int MASK = (1 << BITS) - 1;

    private final DriveBlockEntity drive;
    private final Slot[] slots = new Slot[DriveBlockEntity.SLOTS];
    private final Task task = new Task();
    private int nextSlot;

    CellTransfer(DriveBlockEntity drive) {
        this.drive = drive;
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
    }

    /** The drive's task; scheduled once per block entity. */
    StorageTask task() {
        return task;
    }

    /** {@link #NONE}, {@link #EMPTY} or {@link #FILL} for {@code slot}. */
    public int mode(int slot) {
        return slots[slot].mode;
    }

    /** {@link #NONE}, {@link #FINISHED} or {@link #BLOCKED}: how {@code slot}'s last transfer ended; not saved. */
    public int result(int slot) {
        return slots[slot].result;
    }

    /** Whether any slot transfers. */
    public boolean active() {
        for (Slot slot : slots) {
            if (slot.mode != NONE) {
                return true;
            }
        }
        return false;
    }

    /** The modes packed two bits per slot, slot 0 lowest; saved and synced to the screen. */
    public int packedModes() {
        int packed = 0;
        for (int i = 0; i < slots.length; i++) {
            packed |= slots[i].mode << (i * BITS);
        }
        return packed;
    }

    /** The results packed like {@link #packedModes}. */
    public int packedResults() {
        int packed = 0;
        for (int i = 0; i < slots.length; i++) {
            packed |= slots[i].result << (i * BITS);
        }
        return packed;
    }

    /** The mode each slot's {@link #result} belongs to, packed like {@link #packedModes}; {@link #NONE} without one. */
    public int packedEnded() {
        int packed = 0;
        for (int i = 0; i < slots.length; i++) {
            packed |= slots[i].ended << (i * BITS);
        }
        return packed;
    }

    /** {@code slot}'s value in a {@link #packedModes} or {@link #packedResults} int. */
    public static int unpack(int packed, int slot) {
        return (packed >>> (slot * BITS)) & MASK;
    }

    /** Restores saved modes; an unknown value reads as {@link #NONE}. The drive mounts its cells afterwards. */
    void loadModes(int packed) {
        for (int i = 0; i < slots.length; i++) {
            int mode = unpack(packed, i);
            slots[i].reset(mode == EMPTY || mode == FILL ? mode : NONE);
            slots[i].result = NONE;
            slots[i].ended = NONE;
        }
    }

    /**
     * Starts {@code mode} in {@code slot}, replacing what ran there, and wakes the task. False, changing nothing, when
     * no working cell is in the slot or the mode is not {@link #EMPTY} or {@link #FILL}.
     */
    boolean start(int slot, int mode) {
        CellInventory cell = drive.cell(slot);
        if (cell == null || (mode != EMPTY && mode != FILL)) {
            return false;
        }
        slots[slot].reset(mode);
        slots[slot].result = NONE;
        slots[slot].ended = NONE;
        cell.setDraining(mode == EMPTY);
        drive.changedTransfers();
        drive.wake(task);
        return true;
    }

    /** Stops {@code slot}'s transfer with no result; what already moved stays moved. */
    void stop(int slot) {
        end(slot, NONE);
    }

    /** The slot's cell went out or changed: its transfer and result go. */
    void cellChanged(int slot) {
        if (slots[slot].mode != NONE || slots[slot].result != NONE) {
            slots[slot].reset(NONE);
            slots[slot].result = NONE;
            slots[slot].ended = NONE;
            drive.changedTransfers();
        }
    }

    /** Whether {@code slot}'s freshly mounted cell must refuse inserts. */
    boolean draining(int slot) {
        return slots[slot].mode == EMPTY;
    }

    private void end(int slot, int result) {
        Slot state = slots[slot];
        if (state.mode == NONE && state.result == result) {
            return;
        }
        CellInventory cell = drive.cell(slot);
        if (cell != null) {
            cell.setDraining(false);
        }
        state.ended = result == NONE ? NONE : state.mode;
        state.reset(NONE);
        state.result = result;
        drive.changedTransfers();
    }

    /** One slot's pass: the keys it goes over, where it is, and whether anything moved since it started. */
    private static final class Slot {
        int mode;
        int result;
        /** The mode {@link #result} belongs to. */
        int ended;
        final ObjectArrayList<StackKey> keys = new ObjectArrayList<>();
        int cursor;
        boolean started;
        boolean moved;

        void reset(int mode) {
            this.mode = mode;
            keys.clear();
            cursor = 0;
            started = false;
            moved = false;
        }
    }

    private final class Task implements StorageTask, StorageProvider.Visitor {
        private ObjectArrayList<StackKey> collecting = new ObjectArrayList<>();

        @Override
        public void visit(StackKey key, long amount) {
            collecting.add(key);
        }

        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            if (!active()) {
                return WAIT;
            }
            if (!transfer.canOperate()) {
                return Math.max(1, transfer.retryTicks());
            }
            int budget = StorageServerConfig.CELL_TRANSFER_OPERATIONS.getAsInt();
            int first = nextSlot;
            nextSlot = (nextSlot + 1) % slots.length;
            for (int n = 0; n < slots.length && budget > 0; n++) {
                int slot = (first + n) % slots.length;
                if (slots[slot].mode == NONE) {
                    continue;
                }
                budget = runSlot(slot, transfer, budget);
                if (budget < 0) {
                    return Math.max(1, transfer.retryTicks());
                }
            }
            return active() ? NEXT_TICK : WAIT;
        }

        /**
         * Spends up to {@code budget} operations on {@code slot}; returns the budget left, or -1 when the network
         * refused for lack of energy (the task waits).
         */
        private int runSlot(int slot, StorageTransfer transfer, int budget) {
            Slot state = slots[slot];
            CellInventory cell = drive.cell(slot);
            if (cell == null) {
                end(slot, BLOCKED);
                return budget;
            }
            while (budget > 0 && state.mode != NONE) {
                if (state.cursor >= state.keys.size()) {
                    if (state.started && !state.moved) {
                        end(slot, finished(state.mode, cell) ? FINISHED : BLOCKED);
                        return budget;
                    }
                    if (finished(state.mode, cell)) {
                        end(slot, FINISHED);
                        return budget;
                    }
                    startPass(state, cell, transfer.network().contents());
                    if (state.keys.isEmpty()) {
                        end(slot, BLOCKED);
                        return budget;
                    }
                }
                StackKey key = state.keys.get(state.cursor);
                long moved = state.mode == EMPTY ? emptyOne(cell, key, transfer) : fillOne(cell, key, transfer);
                budget--;
                if (moved > 0) {
                    state.moved = true;
                    if (state.mode == EMPTY && cell.amount(key) <= 0) {
                        state.cursor++;
                    }
                } else if (transfer.retryTicks() > 0) {
                    return -1;
                } else {
                    state.cursor++;
                }
            }
            return budget;
        }

        private void startPass(Slot state, CellInventory cell, StorageContents contents) {
            state.keys.clear();
            state.cursor = 0;
            state.started = true;
            state.moved = false;
            if (state.mode == EMPTY) {
                collecting = state.keys;
                cell.forEachStored(this);
                return;
            }
            IntIterator ids = contents.snapshot().totals().keySet().iterator();
            while (ids.hasNext()) {
                StackKey key = contents.key(ids.nextInt());
                if (key != null) {
                    state.keys.add(key);
                }
            }
        }
    }

    private static boolean finished(int mode, CellInventory cell) {
        return mode == EMPTY ? cell.kinds() - cell.keptKinds() == 0 : cell.usedBytes() >= cell.totalBytes();
    }

    /**
     * One batch of {@code key} from the cell into the network: the network takes what it has room for (the draining
     * cell refuses its share), then the cell gives exactly that. Returns what moved.
     */
    static long emptyOne(CellInventory cell, StackKey key, StorageTransfer transfer) {
        long want = Math.min(cell.amount(key), transfer.batchLimit(key.kind()));
        if (want <= 0) {
            return 0;
        }
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, want, root);
            if (stored <= 0 || cell.extract(key, stored, root) != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /**
     * One batch of {@code key} from the network into the cell: what the cell would store is simulated, the network
     * gives up to that from its other providers, and the cell must then store exactly that. Measured by the cell's
     * count, so a void-overflow cell never reports as stored what it would destroy. Returns what moved.
     */
    static long fillOne(CellInventory cell, StackKey key, StorageTransfer transfer) {
        long want = transfer.batchLimit(key.kind());
        try (Transaction root = Transaction.openRoot()) {
            long room;
            try (Transaction simulation = Transaction.open(root)) {
                long before = cell.amount(key);
                cell.insert(key, want, simulation);
                room = cell.amount(key) - before;
            }
            if (room <= 0) {
                return 0;
            }
            long taken;
            cell.holdExtracts(true);
            try {
                taken = transfer.extract(key, room, root);
            } finally {
                cell.holdExtracts(false);
            }
            if (taken <= 0) {
                return 0;
            }
            long before = cell.amount(key);
            cell.insert(key, taken, root);
            if (cell.amount(key) - before != taken) {
                return 0;
            }
            root.commit();
            return taken;
        }
    }
}
