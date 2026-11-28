package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.api.resource.EnergyKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; port moves are transfer transactions (ADR-0002, ADR-0018).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The energy side of a port: standalone MU buffers ({@link EnergyEndpoint}s with the buffer role, outside any network)
 * against the FE handlers beside it, found through {@link FeLookups}. FE handlers always take part in transactions, so
 * every operation is one root transaction with one move ({@link EnergyHandlerUtil#move}); the buffers' levels are not
 * journaled by the endpoints, so this channel keeps its own journal of them and of the {@link EnergyRemainder}, and a
 * rollback puts both back exactly. FE are converted at the configured rate; the fraction of one MU a conversion leaves
 * stays in the remainder for the next operation, and is saved with the port, so energy is never created or lost. One
 * operation moves at most the per-operation MU (in FE, rounded down, but at least 1 FE). A push empties the pushing
 * buffers in the order they were added; a pull fills the pulling buffers first-fit, and between two Lib ports the
 * pusher fills the other port's pulling buffers. An endpoint that joined a network after the port was built is left
 * alone. Read-only looks come first where the port's own side decides, so those open no transaction. Server thread
 * only.
 */
final class EnergyChannel extends PortChannel {
    /** {@link #push}/{@link #pull}: the port's side has nothing to do. */
    private static final long NOTHING = 0;
    /** {@link #push}/{@link #pull}: nothing to take from the partner now, but it may fill without a signal. */
    private static final long EMPTY = -1;
    /** {@link #push}/{@link #pull}: something to move, but the other side refused it. */
    private static final long REFUSED = -2;
    private static final String REMAINDER = "energy_remainder";
    private static final String HELD = "held";
    private static final String HELD_FE = "fe";
    private static final String HELD_MU = "mu";

    private final EnergyEndpoint[] endpoints;
    private final Runnable[] onChange;
    private final boolean[] pushes;
    private final boolean[] pulls;
    private final EnergyRemainder remainder = new EnergyRemainder();
    private final Journal journal = new Journal();
    private final EnergyHandler pushSide = new PushSide();
    private final EnergyHandler pullSide = new PullSide();
    private final @Nullable Face[] faces = new Face[6];
    private boolean filterAllows = true;

    EnergyChannel(EnergyEndpoint[] endpoints, Runnable[] onChange, boolean[] pushes, boolean[] pulls,
            Set<PortFaceMode> allowedModes, long ownerCap) {
        super(ResourceKinds.ENERGY, allowedModes, ownerCap);
        this.endpoints = endpoints.clone();
        this.onChange = onChange.clone();
        this.pushes = pushes;
        this.pulls = pulls;
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

    /** Energy has one key: a filter either lets it move or keeps it in. */
    @Override
    void settingsChanged(PortSettings settings) {
        PortFilter<EnergyKey> filter = settings.filter(ResourceKinds.ENERGY).orElse(null);
        filterAllows = filter == null || filter.test(EnergyKey.INSTANCE);
    }

    @Override
    void forgetFilterAnswers() {
    }

    @Override
    long configPerOp() {
        return ServerConfig.TRANSFER_ENERGY_PER_OP.getAsLong();
    }

    /** Saves the remainder with the rate it was counted at, in its own compound; nothing while there is none. */
    @Override
    void save(ValueOutput output) {
        long held = remainder.held();
        if (held > 0) {
            ValueOutput child = output.child(REMAINDER);
            child.putLong(HELD, held);
            child.putInt(HELD_FE, remainder.rate().fe());
            child.putInt(HELD_MU, remainder.rate().mu());
        }
    }

    /** Loads what {@link #save} wrote; missing or corrupt data (a rate below 1, units out of range) is no remainder. */
    @Override
    void load(ValueInput input) {
        Optional<ValueInput> child = input.child(REMAINDER);
        long held = child.map(c -> c.getLongOr(HELD, 0L)).orElse(0L);
        int fe = child.map(c -> c.getIntOr(HELD_FE, 0)).orElse(0);
        int mu = child.map(c -> c.getIntOr(HELD_MU, 0)).orElse(0);
        if (held > 0 && fe >= 1 && mu >= 1) {
            remainder.use(new FeRate(fe, mu));
            remainder.restore(held);
        } else {
            remainder.restore(0);
        }
    }

    @Override
    int operate(LibTransferPort port, int packed, long amount) {
        if (packed == PortFaces.NONE) {
            status = PortStatus.OFF;
            return NONE;
        }
        remainder.use(ServerConfig.feRate());
        int feLimit = feLimit(amount);
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
                // As for items and tanks: a chunk back at full status from memory invalidates no capability cache, so
                // the face makes a new one then, and polls for that only while it has work.
                face.stale = true;
                poll |= mode == PortFaceMode.PUSH ? remainder.feOut(pushStock()) > 0 : remainder.feIn(pullRoom()) > 0;
                continue;
            }
            // Covers a chunk reloaded from disk (a new block entity), which a GameTest cannot reach; kept on purpose.
            if (face.stale) {
                face.live = false;
                face = new Face(port, port.level(), port.neighbourPos(i), FACES[i].getOpposite());
                faces[i] = face;
            }
            LibTransferPort neighbour = port.neighbour(i);
            EnergyChannel other = neighbour == null ? null : sameKind(neighbour.channel(ResourceKinds.ENERGY));
            PortFaceMode facing = other == null || neighbour == null
                    ? PortFaceMode.OFF
                    : neighbour.settings().mode(ResourceKinds.ENERGY, FACES[i].getOpposite());
            if (!mode.movesAgainst(facing)) {
                continue;
            }
            // The capability shows the neighbour is really there, a neighbouring port included.
            EnergyHandler handler = face.lookup.find();
            if (handler == null) {
                continue;
            }
            long result;
            if (facing == PortFaceMode.PULL && other != null) {
                // A port's pulling face: into its pulling buffers, never its plain capability (ADR-0018).
                result = other.filterAllows ? push(other.pullSide, other, feLimit) : REFUSED;
            } else {
                result = mode == PortFaceMode.PUSH ? push(handler, null, feLimit) : pull(handler, feLimit);
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

    /** {@code mu} per operation in whole FE, rounded down but at least 1 FE, as Lib's FE handlers do. */
    private int feLimit(long mu) {
        FeRate rate = remainder.rate();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, rate.wholeFe(rate.unitsOfMu(mu))));
    }

    private static @Nullable EnergyChannel sameKind(@Nullable PortChannel channel) {
        return channel instanceof EnergyChannel energy ? energy : null;
    }

    /**
     * Moves up to {@code fe} FE from the pushing buffers into {@code target}, which is the pulling side of {@code port}
     * when that is not null; returns what moved or a code below 1.
     */
    private long push(EnergyHandler target, @Nullable EnergyChannel port, int fe) {
        if (!filterAllows || remainder.feOut(pushStock()) <= 0) {
            return NOTHING;
        }
        if (port != null) {
            port.remainder.use(remainder.rate());
            if (port.remainder.feIn(port.pullRoom()) <= 0) {
                return REFUSED;
            }
        }
        long moved;
        try (Transaction root = Transaction.openRoot()) {
            moved = EnergyHandlerUtil.move(pushSide, target, fe, root);
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** Moves up to {@code fe} FE from {@code source} into the pulling buffers; returns what moved or a code. */
    private long pull(EnergyHandler source, int fe) {
        if (!filterAllows || remainder.feIn(pullRoom()) <= 0) {
            return NOTHING;
        }
        if (source.getAmountAsLong() <= 0) {
            return EMPTY;
        }
        long moved;
        try (Transaction root = Transaction.openRoot()) {
            moved = EnergyHandlerUtil.move(source, pullSide, fe, root);
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** Whether the port may move {@code b}'s energy: an endpoint that joined a network since is left alone. */
    private boolean usable(int b) {
        return endpoints[b].network().isEmpty();
    }

    /** MU in the pushing buffers. */
    private long pushStock() {
        long stock = 0;
        for (int b = 0; b < endpoints.length; b++) {
            if (pushes[b] && usable(b)) {
                stock = add(stock, endpoints[b].stored());
            }
        }
        return stock;
    }

    /** MU of room in the pulling buffers. */
    private long pullRoom() {
        long room = 0;
        for (int b = 0; b < endpoints.length; b++) {
            if (pulls[b] && usable(b)) {
                room = add(room, Math.max(0, endpoints[b].capacity() - endpoints[b].stored()));
            }
        }
        return room;
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    @Override
    public String toString() {
        return "EnergyChannel[" + endpoints.length + " buffers]";
    }

    /** The pushing buffers as an FE handler that only gives; FE leave through the remainder. */
    private final class PushSide implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return remainder.feOut(pushStock());
        }

        @Override
        public long getCapacityAsLong() {
            return getAmountAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            int give = (int) Math.min(amount, remainder.feOut(pushStock()));
            if (give <= 0) {
                return 0;
            }
            journal.updateSnapshots(transaction);
            long mu = remainder.takeOut(give);
            for (int b = 0; b < endpoints.length && mu > 0; b++) {
                if (pushes[b] && usable(b)) {
                    mu -= endpoints[b].extract(mu);
                }
            }
            return give;
        }
    }

    /** The pulling buffers as an FE handler that only takes, first-fit; FE enter through the remainder. */
    private final class PullSide implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return remainder.feIn(pullRoom());
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            int take = (int) Math.min(amount, remainder.feIn(pullRoom()));
            if (take <= 0) {
                return 0;
            }
            journal.updateSnapshots(transaction);
            long mu = remainder.putIn(take);
            for (int b = 0; b < endpoints.length && mu > 0; b++) {
                if (pulls[b] && usable(b)) {
                    mu -= endpoints[b].insert(mu);
                }
            }
            return take;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }
    }

    /**
     * Snapshots of the remainder and every buffer's level: endpoints keep no journal of their own. A rollback puts them
     * back exactly (a standalone endpoint's level changes only through its owner, never inside the port's transaction).
     * After a root commit that changed a buffer, that buffer's owner hears of it; a change of the remainder alone tells
     * the first buffer's owner, since the remainder is saved with the port. Pooled snapshots, so moving every tick
     * allocates nothing once warm.
     */
    private final class Journal extends SnapshotJournal<long[]> {
        private final ObjectArrayList<long[]> pool = new ObjectArrayList<>();

        @Override
        protected long[] createSnapshot() {
            long[] snapshot = pool.isEmpty() ? new long[endpoints.length + 1] : pool.pop();
            snapshot[0] = remainder.held();
            for (int b = 0; b < endpoints.length; b++) {
                snapshot[b + 1] = endpoints[b].stored();
            }
            return snapshot;
        }

        @Override
        protected void revertToSnapshot(long[] snapshot) {
            remainder.restore(snapshot[0]);
            for (int b = 0; b < endpoints.length; b++) {
                endpoints[b].setStored(snapshot[b + 1]);
            }
        }

        @Override
        protected void releaseSnapshot(long[] snapshot) {
            pool.push(snapshot);
        }

        @Override
        protected void onRootCommit(long[] originalState) {
            boolean told = false;
            for (int b = 0; b < endpoints.length; b++) {
                if (endpoints[b].stored() != originalState[b + 1]) {
                    onChange[b].run();
                    told = true;
                }
            }
            if (!told && remainder.held() != originalState[0]) {
                onChange[0].run();
            }
        }
    }

    /** The FE cache of one active face, with its own validity like the other kinds'. */
    private static final class Face implements BooleanSupplier, Runnable {
        private final LibTransferPort port;
        final CachedBlockLookup<EnergyHandler> lookup;
        boolean live = true;
        /** The neighbour was seen outside full status: the cache may be stale, make a new one when it is back. */
        boolean stale;

        Face(LibTransferPort port, ServerLevel level, BlockPos pos, Direction side) {
            this.port = port;
            this.lookup = FeLookups.BLOCK.cache(level, pos, side, this, this);
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
