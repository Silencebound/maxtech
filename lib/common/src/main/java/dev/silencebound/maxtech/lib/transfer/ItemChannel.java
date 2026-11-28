package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.inventory.RoleView;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
// PLATFORM-LEAK: port moves are NeoForge transfer transactions against the real handlers (ADR-0002, ADR-0018).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The item side of a port: Lib item slots (through their automation view, so slot roles hold) against item handlers
 * beside it. Every operation is one root transaction: a push inserts into the target, then takes exactly what went in
 * from the port's slots; a pull inserts into the port's slots, then takes exactly that from the source in a nested
 * transaction per source index, which rolls back alone when the source gives less. Only committed amounts count. Item
 * handlers always take part in transactions, so the rule against two handlers without them never applies here. A
 * read-only look comes first, so a blocked port opens no transaction. Server thread only.
 */
final class ItemChannel extends PortChannel {
    /** {@link #push}/{@link #pull}: the port's side has nothing to do. */
    private static final int NOTHING = 0;
    /** {@link #push}/{@link #pull}: nothing to take from the partner now, but it may fill without a signal. */
    private static final int EMPTY = -1;
    /** {@link #push}/{@link #pull}: something to move, but the other side refused it. */
    private static final int REFUSED = -2;

    private final RoleView[] buffers;
    private final boolean[] pushes;
    private final boolean[] pulls;
    private final @Nullable Face[] faces = new Face[6];
    private @Nullable PortFilter<ItemKey> filter;
    /** The resource the filter last answered for, by identity, so a slot full of one item is tested once. */
    private @Nullable ItemResource tested;
    private boolean testedAllowed;

    ItemChannel(RoleView[] buffers, boolean[] pushes, boolean[] pulls, Set<PortFaceMode> allowed, long ownerCap) {
        super(ResourceKinds.ITEM, allowed, ownerCap);
        this.buffers = buffers;
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

    @Override
    void settingsChanged(PortSettings settings) {
        filter = settings.filter(ResourceKinds.ITEM).orElse(null);
        tested = null;
    }

    @Override
    long configPerOp() {
        return ServerConfig.TRANSFER_ITEMS_PER_OP.getAsInt();
    }

    @Override
    int operate(LibTransferPort port, int packed, long limit) {
        if (packed == PortFaces.NONE) {
            status = PortStatus.OFF;
            return NONE;
        }
        int amount = (int) Math.min(Integer.MAX_VALUE, limit);
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
                // Below full status the chunk may stay in memory, and a chunk coming back from there to full status
                // invalidates no capability cache, so the port polls and makes a new cache once it is back.
                face.stale = true;
                poll = true;
                continue;
            }
            if (face.stale) {
                face.live = false;
                face = new Face(port, port.level(), port.neighbourPos(i), FACES[i].getOpposite());
                faces[i] = face;
            }
            LibTransferPort neighbour = port.neighbour(i);
            ItemChannel other = neighbour == null ? null : neighbour.items();
            PortFaceMode facing = other == null || neighbour == null
                    ? PortFaceMode.OFF
                    : neighbour.settings().mode(ResourceKinds.ITEM, FACES[i].getOpposite());
            if (!mode.movesAgainst(facing)) {
                continue;
            }
            int result;
            if (facing == PortFaceMode.PULL && other != null) {
                // A port's pulling face: into its pulling buffers, never its plain capability (ADR-0018).
                result = push(null, other, amount);
            } else {
                ResourceHandler<ItemResource> handler = face.lookup.find();
                if (handler == null) {
                    continue;
                }
                result = mode == PortFaceMode.PUSH ? push(handler, null, amount) : pull(handler, amount);
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

    /**
     * Moves up to {@code amount} from the pushing buffers into {@code target}, or into the pulling buffers of the port
     * {@code port}; returns what moved or a code below 1.
     */
    private int push(@Nullable ResourceHandler<ItemResource> target, @Nullable ItemChannel port, int amount) {
        boolean work = false;
        boolean fits = false;
        for (int b = 0; b < buffers.length && !fits; b++) {
            if (!pushes[b]) {
                continue;
            }
            RoleView view = buffers[b];
            for (int slot = 0; slot < view.size() && !fits; slot++) {
                ItemResource resource = pushable(view, slot, port);
                if (resource != null) {
                    work = true;
                    fits = port != null ? port.mayFitPull(resource) : target != null && mayFit(target, resource);
                }
            }
        }
        if (!work) {
            return NOTHING;
        }
        if (!fits) {
            return REFUSED;
        }
        int moved = 0;
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
                    int put = port != null
                            ? port.insertPull(resource, offer, root)
                            : target != null ? insertStacking(target, resource, offer, root) : 0;
                    if (put <= 0) {
                        continue;
                    }
                    if (view.extract(slot, resource, put, root) != put) {
                        // The port's own slot gave less than it holds: roll everything back, count nothing.
                        return REFUSED;
                    }
                    moved += put;
                }
            }
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** The resource in {@code slot} if it may be pushed (output role, filters of this port and {@code port}). */
    private @Nullable ItemResource pushable(RoleView view, int slot, @Nullable ItemChannel port) {
        if (!view.canExtract(slot)) {
            return null;
        }
        ItemResource resource = view.getResource(slot);
        if (resource.isEmpty() || view.getAmountAsLong(slot) <= 0 || !allows(resource)
                || (port != null && !port.allows(resource))) {
            return null;
        }
        return resource;
    }

    /** Moves up to {@code amount} from {@code source} into the pulling buffers; returns what moved or a code. */
    private int pull(ResourceHandler<ItemResource> source, int amount) {
        if (!pullRoom()) {
            return NOTHING;
        }
        int size = source.size();
        boolean held = false;
        boolean fits = false;
        for (int index = 0; index < size && !fits; index++) {
            ItemResource resource = source.getResource(index);
            if (resource.isEmpty() || source.getAmountAsLong(index) <= 0) {
                continue;
            }
            held = true;
            fits = allows(resource) && mayFitPull(resource);
        }
        if (!held) {
            return EMPTY;
        }
        if (!fits) {
            return REFUSED;
        }
        int moved = 0;
        try (Transaction root = Transaction.openRoot()) {
            for (int index = 0; index < size && moved < amount; index++) {
                ItemResource resource = source.getResource(index);
                if (resource.isEmpty() || !allows(resource) || !mayFitPull(resource)) {
                    continue;
                }
                int offer = (int) Math.min(amount - moved, source.getAmountAsLong(index));
                if (offer <= 0) {
                    continue;
                }
                try (Transaction part = Transaction.open(root)) {
                    int put = insertPull(resource, offer, part);
                    if (put > 0 && source.extract(index, resource, put, part) == put) {
                        part.commit();
                        moved += put;
                    }
                }
            }
            if (moved > 0) {
                root.commit();
            }
        }
        return moved > 0 ? moved : REFUSED;
    }

    /** Inserts into the pulling buffers, first-fit in the order they were added; returns what went in. */
    int insertPull(ItemResource resource, int amount, TransactionContext transaction) {
        int inserted = 0;
        for (int b = 0; b < buffers.length && inserted < amount; b++) {
            if (pulls[b]) {
                inserted += insertStacking(buffers[b], resource, amount - inserted, transaction);
            }
        }
        return inserted;
    }

    /** A read-only look whether a pulling buffer's input slot may take some of {@code resource}. */
    boolean mayFitPull(ItemResource resource) {
        for (int b = 0; b < buffers.length; b++) {
            if (!pulls[b]) {
                continue;
            }
            RoleView view = buffers[b];
            for (int slot = 0; slot < view.size(); slot++) {
                if (!view.canInsert(slot)) {
                    continue;
                }
                ItemResource held = view.getResource(slot);
                if (held.isEmpty()
                        ? view.isValid(slot, resource) && view.getCapacityAsLong(slot, resource) > 0
                        : held.equals(resource) && view.getAmountAsLong(slot) < view.getCapacityAsLong(slot, held)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether any input slot of a pulling buffer has room for anything. */
    private boolean pullRoom() {
        for (int b = 0; b < buffers.length; b++) {
            if (!pulls[b]) {
                continue;
            }
            RoleView view = buffers[b];
            for (int slot = 0; slot < view.size(); slot++) {
                if (!view.canInsert(slot)) {
                    continue;
                }
                ItemResource held = view.getResource(slot);
                if (held.isEmpty()
                        ? view.getCapacityAsLong(slot, held) > 0
                        : view.getAmountAsLong(slot) < view.getCapacityAsLong(slot, held)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether this port's filter lets {@code resource} move; allocates only for a resource not asked about last. */
    @SuppressWarnings("ReferenceEquality") // the same resource instance gives the same answer
    boolean allows(ItemResource resource) {
        PortFilter<ItemKey> current = filter;
        if (current == null) {
            return true;
        }
        if (resource != tested) {
            testedAllowed = current.test(StackKeys.of(resource));
            tested = resource;
        }
        return testedAllowed;
    }

    /**
     * A read-only look whether {@code target} may take any of {@code resource}: an empty index that accepts it, or one
     * holding it below its capacity (or of unknown capacity). A full target costs one walk and no transaction.
     */
    private static boolean mayFit(ResourceHandler<ItemResource> target, ItemResource resource) {
        int size = target.size();
        for (int index = 0; index < size; index++) {
            ItemResource held = target.getResource(index);
            if (held.isEmpty()) {
                if (target.isValid(index, resource)) {
                    return true;
                }
            } else if (held.equals(resource)) {
                long capacity = target.getCapacityAsLong(index, resource);
                if (capacity <= 0 || target.getAmountAsLong(index) < capacity) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Inserts into indices holding something first, then empty ones, all in {@code transaction}; like
     * {@code ResourceHandlerUtil.insertStacking} without its nested transaction, whose stack walk is costly.
     */
    private static int insertStacking(ResourceHandler<ItemResource> handler, ItemResource resource, int amount,
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

    /**
     * The item handler cache of one active face, with its own validity so a face turned off or a stopped port never
     * hears from its old cache. The level keeps the listener weakly; the channel keeps the face.
     */
    private static final class Face implements BooleanSupplier, Runnable {
        private final LibTransferPort port;
        final CachedBlockLookup<ResourceHandler<ItemResource>> lookup;
        boolean live = true;
        /** The neighbour was seen outside full status: the cache may be stale, make a new one when it is back. */
        boolean stale;

        Face(LibTransferPort port, ServerLevel level, BlockPos pos, Direction side) {
            this.port = port;
            this.lookup = Platform.get().transfer().items().cache(level, pos, side, this, this);
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
