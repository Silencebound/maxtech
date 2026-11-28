package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.inventory.LibResourceTank;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.KindLookup;
import dev.silencebound.maxtech.lib.platform.KindStorage;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
// PLATFORM-LEAK: port moves are NeoForge transfer transactions against the real handlers (ADR-0002, ADR-0018).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The side of a port for one tank kind (fluids, or a compat module's kind): Lib tanks against the kind's storage beside
 * it, reached through {@link KindLookup} (the kind's {@code KindBridge} on NeoForge). Every operation is one root
 * transaction made of {@link KindMoves}, which count only what both sides moved and touch a side without transactions
 * last; after a move with such a side the operation commits at once. A push empties the pushing tanks in the order they
 * were added; a pull fills the pulling tanks first-fit, and between two Lib ports the pusher fills the other port's
 * pulling tanks. Tanks with unknown contents are never pushed from or filled. A read-only look comes first where the
 * port's own side decides (nothing to push, no room to pull into, an empty source), so those open no transaction; a
 * full foreign target can only be found by simulating, inside the operation's transaction. Server thread only.
 *
 * @param <K>
 *            the key type of the kind
 */
final class TankChannel<K> extends PortChannel {
    /** {@link #push}/{@link #pull}: the port's side has nothing to do. */
    private static final long NOTHING = 0;
    /** {@link #push}/{@link #pull}: nothing to take from the partner now, but it may fill without a signal. */
    private static final long EMPTY = -1;
    /** {@link #push}/{@link #pull}: something to move, but the other side refused it. */
    private static final long REFUSED = -2;

    private final ResourceKind<K> tankKind;
    private final List<LibResourceTank<K>> tanks;
    private final List<KindStorage<K>> storages;
    private final boolean[] pushes;
    private final boolean[] pulls;
    private final KindStorage<K> pullSide = new PullTanks();
    private final KindLookup<K> lookup;
    private final @Nullable Face[] faces = newFaces();
    private @Nullable PortFilter<K> filter;
    /** The key the filter last answered for, so a tank full of one resource is tested once. */
    private @Nullable K tested;
    private boolean testedAllowed;

    TankChannel(ResourceKind<K> kind, List<LibResourceTank<K>> tanks, boolean[] pushes, boolean[] pulls,
            KindLookup<K> lookup, Set<PortFaceMode> allowed, long ownerCap) {
        super(kind, allowed, ownerCap);
        this.tankKind = kind;
        this.tanks = List.copyOf(tanks);
        KindStorage<?>[] wrapped = new KindStorage<?>[tanks.size()];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = new OwnTank<>(tanks.get(i));
        }
        @SuppressWarnings("unchecked") // each wrapper holds a tank of this kind
        List<KindStorage<K>> own = (List<KindStorage<K>>) (List<?>) List.of(wrapped);
        this.storages = own;
        this.pushes = pushes;
        this.pulls = pulls;
        this.lookup = lookup;
    }

    @SuppressWarnings("unchecked") // an array of this channel's own inner class
    private @Nullable Face[] newFaces() {
        return (Face[]) Array.newInstance(Face.class, 6);
    }

    @Override
    void open(LibTransferPort port, ServerLevel level, BlockPos pos, int packed) {
        for (int i = 0; i < 6; i++) {
            boolean active = PortFaces.get(packed, FACES[i]) != PortFaceMode.OFF;
            Face face = faces[i];
            if (active && face == null) {
                faces[i] = new Face(port, level, pos.relative(FACES[i]), FACES[i].getOpposite());
            } else if (!active && face != null) {
                face.live = false;
                faces[i] = null;
            }
        }
    }

    @Override
    void close() {
        for (int i = 0; i < 6; i++) {
            Face face = faces[i];
            if (face != null) {
                face.live = false;
                faces[i] = null;
            }
        }
        status = PortStatus.OFF;
    }

    @Override
    void settingsChanged(PortSettings settings) {
        filter = settings.filter(tankKind).orElse(null);
        tested = null;
    }

    @Override
    void forgetFilterAnswers() {
        tested = null;
    }

    /** Fluids and every other tank kind move up to {@code transfer.fluidPerOp} per operation. */
    @Override
    long configPerOp() {
        return ServerConfig.TRANSFER_FLUID_PER_OP.getAsInt();
    }

    @Override
    int operate(LibTransferPort port, int packed, long amount) {
        if (packed == PortFaces.NONE) {
            status = PortStatus.OFF;
            return NONE;
        }
        boolean refused = false;
        boolean poll = false;
        for (int step = 0; step < 6; step++) {
            int i = cursor + step < 6 ? cursor + step : cursor + step - 6;
            PortFaceMode mode = PortFaces.get(packed, FACES[i]);
            Face face = faces[i];
            if (mode == PortFaceMode.OFF || face == null) {
                continue;
            }
            if (!port.neighbourLoaded(i)) {
                // As for items: a chunk back at full status from memory invalidates no capability cache, so the face
                // makes a new one then, and polls for that only while it has work.
                face.stale = true;
                poll |= mode == PortFaceMode.PUSH ? pushWork() : pullRoom();
                continue;
            }
            // Covers a chunk reloaded from disk (a new block entity), which a GameTest cannot reach; kept on purpose.
            if (face.stale) {
                face.live = false;
                face = new Face(port, port.level(), port.neighbourPos(i), FACES[i].getOpposite());
                faces[i] = face;
            }
            LibTransferPort neighbour = port.neighbour(i);
            TankChannel<K> other = neighbour == null ? null : sameKind(neighbour.channel(tankKind));
            PortFaceMode facing = other == null || neighbour == null
                    ? PortFaceMode.OFF
                    : neighbour.settings().mode(tankKind, FACES[i].getOpposite());
            if (!mode.movesAgainst(facing)) {
                continue;
            }
            // The capability shows the neighbour is really there, a neighbouring port included.
            KindStorage<K> storage = face.lookup.find();
            if (storage == null) {
                continue;
            }
            long result;
            if (facing == PortFaceMode.PULL && other != null) {
                // A port's pulling face: into its pulling tanks, never its plain capability (ADR-0018).
                result = push(other.pullSide, other, amount);
            } else {
                result = mode == PortFaceMode.PUSH ? push(storage, null, amount) : pull(storage, amount);
            }
            if (result > 0) {
                cursor = i + 1 < 6 ? i + 1 : 0;
                status = PortStatus.MOVING;
                return MOVED;
            }
            refused |= result == REFUSED;
            poll |= result == EMPTY;
        }
        status = refused ? PortStatus.BLOCKED : PortStatus.IDLE;
        return refused || poll ? POLL : NONE;
    }

    @SuppressWarnings("unchecked") // a channel of the same kind has the same key type
    private @Nullable TankChannel<K> sameKind(@Nullable PortChannel channel) {
        return channel instanceof TankChannel<?> tank ? (TankChannel<K>) tank : null;
    }

    /**
     * Moves up to {@code amount} from the pushing tanks into {@code target}, which is the pulling side of {@code port}
     * when that is not null; returns what moved or a code below 1.
     */
    private long push(KindStorage<K> target, @Nullable TankChannel<K> port, long amount) {
        boolean work = false;
        boolean fits = false;
        for (int b = 0; b < tanks.size() && !fits; b++) {
            K key = pushes[b] ? pushable(tanks.get(b), port) : null;
            if (key != null) {
                work = true;
                fits = port == null || port.mayFitPull(key);
            }
        }
        if (!work) {
            return NOTHING;
        }
        if (!fits) {
            return REFUSED;
        }
        boolean foreign = !target.transactional();
        long moved = 0;
        try (Transaction root = Transaction.openRoot()) {
            for (int b = 0; b < tanks.size() && moved < amount; b++) {
                if (!pushes[b]) {
                    continue;
                }
                LibResourceTank<K> tank = tanks.get(b);
                K key = pushable(tank, port);
                if (key == null) {
                    continue;
                }
                long step = KindMoves.move(storages.get(b), target, key, Math.min(amount - moved, tank.amount()), root);
                moved += step;
                if (foreign && step > 0) {
                    // The foreign side cannot roll back: commit now, nothing more in this transaction.
                    break;
                }
            }
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** The key in {@code tank} if it may be pushed (known contents, filters of this port and {@code port}). */
    private @Nullable K pushable(LibResourceTank<K> tank, @Nullable TankChannel<K> port) {
        if (tank.unknown()) {
            return null;
        }
        K key = tank.key();
        if (key == null || tank.amount() <= 0 || !allows(key) || (port != null && !port.allows(key))) {
            return null;
        }
        return key;
    }

    /** Whether a pushing tank holds anything this port's filter lets out. */
    private boolean pushWork() {
        for (int b = 0; b < tanks.size(); b++) {
            if (pushes[b] && pushable(tanks.get(b), null) != null) {
                return true;
            }
        }
        return false;
    }

    /** Moves up to {@code amount} from {@code source} into the pulling tanks; returns what moved or a code. */
    private long pull(KindStorage<K> source, long amount) {
        if (!pullRoom()) {
            return NOTHING;
        }
        int size = source.size();
        boolean held = false;
        boolean fits = false;
        for (int index = 0; index < size && !fits; index++) {
            K key = source.resourceAt(index);
            if (key == null || source.amountAt(index) <= 0) {
                continue;
            }
            held = true;
            fits = allows(key) && mayFitPull(key);
        }
        if (!held) {
            return EMPTY;
        }
        if (!fits) {
            return REFUSED;
        }
        boolean foreign = !source.transactional();
        long moved = 0;
        try (Transaction root = Transaction.openRoot()) {
            for (int index = 0; index < size && moved < amount; index++) {
                K key = source.resourceAt(index);
                long available = source.amountAt(index);
                if (key == null || available <= 0 || !allows(key) || !mayFitPull(key)) {
                    continue;
                }
                long step = KindMoves.move(source, pullSide, key, Math.min(amount - moved, available), root);
                moved += step;
                if (foreign && step > 0) {
                    break;
                }
            }
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** A read-only look whether a pulling tank may take some of {@code key}. */
    boolean mayFitPull(K key) {
        for (int b = 0; b < tanks.size(); b++) {
            if (!pulls[b]) {
                continue;
            }
            LibResourceTank<K> tank = tanks.get(b);
            if (tank.unknown() || !tank.accepts(key)) {
                continue;
            }
            K held = tank.key();
            if (held == null ? tank.capacity() > 0 : held.equals(key) && tank.amount() < tank.capacity()) {
                return true;
            }
        }
        return false;
    }

    /** Whether any pulling tank has room for anything. */
    private boolean pullRoom() {
        for (int b = 0; b < tanks.size(); b++) {
            LibResourceTank<K> tank = tanks.get(b);
            if (pulls[b] && !tank.unknown() && tank.amount() < tank.capacity()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this port's filter lets {@code key} move; asks the filter only for a key other than the last one. */
    @SuppressWarnings("ReferenceEquality") // the same key instance gives the same answer; equals checks the rest
    boolean allows(K key) {
        PortFilter<K> current = filter;
        if (current == null) {
            return true;
        }
        K last = tested;
        if (key != last && (last == null || !key.equals(last))) {
            testedAllowed = current.test(key);
            tested = key;
        }
        return testedAllowed;
    }

    @Override
    public String toString() {
        return "TankChannel[" + tankKind.id() + ", " + tanks.size() + " tanks]";
    }

    /** One of the port's tanks as a storage for {@link KindMoves}; always transactional. */
    private static final class OwnTank<K> implements KindStorage<K> {
        private final LibResourceTank<K> tank;

        OwnTank(LibResourceTank<K> tank) {
            this.tank = tank;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public @Nullable K resourceAt(int index) {
            return tank.key();
        }

        @Override
        public long amountAt(int index) {
            return tank.amount();
        }

        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            return tank.insert(key, amount, transaction);
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return tank.extract(key, amount, transaction);
        }

        @Override
        public boolean transactional() {
            return true;
        }

        @Override
        public long simulateInsert(K key, long amount, TransactionContext transaction) {
            try (Transaction nested = Transaction.open(transaction)) {
                return tank.insert(key, amount, nested);
            }
        }

        @Override
        public long simulateExtract(K key, long amount, TransactionContext transaction) {
            try (Transaction nested = Transaction.open(transaction)) {
                return tank.extract(key, amount, nested);
            }
        }
    }

    /** The pulling tanks as one storage that fills them first-fit in the order they were added; takes in only. */
    private final class PullTanks implements KindStorage<K> {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public @Nullable K resourceAt(int index) {
            return null;
        }

        @Override
        public long amountAt(int index) {
            return 0;
        }

        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            long inserted = 0;
            for (int b = 0; b < tanks.size() && inserted < amount; b++) {
                if (pulls[b]) {
                    inserted += tanks.get(b).insert(key, amount - inserted, transaction);
                }
            }
            return inserted;
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public boolean transactional() {
            return true;
        }

        @Override
        public long simulateInsert(K key, long amount, TransactionContext transaction) {
            try (Transaction nested = Transaction.open(transaction)) {
                return insert(key, amount, nested);
            }
        }

        @Override
        public long simulateExtract(K key, long amount, TransactionContext transaction) {
            return 0;
        }
    }

    /**
     * The storage cache of one active face, with its own validity like the item side's: a face turned off or a stopped
     * port never hears from its old cache.
     */
    private final class Face implements BooleanSupplier, Runnable {
        private final LibTransferPort port;
        final CachedBlockLookup<KindStorage<K>> lookup;
        boolean live = true;
        /** The neighbour was seen outside full status: the cache may be stale, make a new one when it is back. */
        boolean stale;

        Face(LibTransferPort port, ServerLevel level, BlockPos pos, Direction side) {
            this.port = port;
            this.lookup = TankChannel.this.lookup.cache(level, pos, side, this, this);
        }

        @Override
        public boolean getAsBoolean() {
            return live;
        }

        @Override
        public void run() {
            if (live) {
                port.wakeForEvent();
            }
        }
    }
}
