package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.logic.Pools;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.sensor.MachineReader;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A Logic Controller's machine pools (M7L2 row 17): for each label its graphs name, the ports and interfaces with the
 * label (the members, numbered by position so numbers and the round-robin turn survive a reload), the machine each one
 * serves (the block a port faces; for an interface the first neighbour with a block entity that is not part of a
 * network) and the sensor with the same label that reads that machine, if any.
 * <p>
 * A member is idle by its sensor's value when it has one (the sensor overrides), else by reading its machine directly
 * through a {@link MachineReader}, the sensor's values without a sensor: kept per member until a block or neighbour
 * update invalidates it, read again only when a pool is looked at, at the sensor's costs. A pool is built from the
 * network's label index and built again when the index changed
 * ({@link dev.silencebound.maxtech.storage.network.NetworkLabels#revision()}), the network's devices did
 * ({@link #clear}), an interface member had a neighbour update, or a member or sensor now faces another block than when
 * it was built (turned in place). Server thread only.
 */
final class MachinePools {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** One label's members, their machines and the sensors reading them. */
    static final class Pool {
        private @Nullable NetworkState network;
        private long revision = -1;
        private boolean dirty;
        private NetworkDevice[] members = new NetworkDevice[0];
        private long[] machines = new long[0];
        private @Nullable Direction[] towards = new Direction[0];
        private @Nullable SensorSource[] sensors = new SensorSource[0];
        private long[] sensorTargets = new long[0];
        private int size;

        /** How many members the pool has. */
        int size() {
            return size;
        }

        /** Member {@code i} (0 to {@link #size()} - 1), a port or an interface. */
        NetworkDevice member(int i) {
            return members[i];
        }

        /** The sensor reading member {@code i}'s machine, or null when no sensor with the label does. */
        @Nullable
        SensorSource sensor(int i) {
            return sensors[i];
        }

        /** Whether member {@code i} serves a machine the controller can read directly. */
        boolean hasMachine(int i) {
            return towards[i] != null;
        }
    }

    private final Object2ObjectOpenHashMap<String, Pool> pools = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<NetworkDevice, MachineReader> readers = new Reference2ObjectOpenHashMap<>();
    /** Each member's pool (a device has one label, so one pool), kept with the pools as they are built. */
    private final Reference2ObjectOpenHashMap<NetworkDevice, Pool> memberOf = new Reference2ObjectOpenHashMap<>();
    private final Runnable changed;

    /**
     * @param changed
     *            told when a member's machine changed its capabilities; must only mark work
     */
    MachinePools(Runnable changed) {
        this.changed = changed;
    }

    /** Drops every pool and every direct reading; the next use builds them again (devices changed, network left). */
    void clear() {
        pools.clear();
        memberOf.clear();
        for (MachineReader reader : readers.values()) {
            reader.close();
        }
        readers.clear();
    }

    /**
     * A labelled device had a neighbour update: its direct reading is read again on the next look, and a pool it is an
     * interface member of looks for its machine again. Returns whether it is a member of a pool. One map lookup.
     */
    boolean neighbourChanged(NetworkDevice device) {
        MachineReader reader = readers.get(device);
        if (reader != null) {
            reader.invalidate();
        }
        Pool pool = memberOf.get(device);
        if (pool == null) {
            return false;
        }
        if (!(device instanceof LogicMover mover) || mover.logicTarget() == Long.MIN_VALUE) {
            pool.dirty = true;
        }
        return true;
    }

    /** The pool labelled {@code labelKey} on {@code network}, built again when it may be out of date. */
    Pool pool(NetworkState network, String labelKey) {
        Pool pool = pools.get(labelKey);
        if (pool == null) {
            pool = new Pool();
            pools.put(labelKey, pool);
        }
        long revision = network.labels().revision();
        if (pool.network != network || pool.revision != revision || pool.dirty || turned(pool)) {
            for (int i = 0; i < pool.size; i++) {
                memberOf.remove(pool.members[i]);
            }
            build(pool, network.level(), network.labels().devicesByKey(labelKey));
            for (int i = 0; i < pool.size; i++) {
                memberOf.put(pool.members[i], pool);
            }
            pool.network = network;
            pool.revision = revision;
            pool.dirty = false;
        }
        return pool;
    }

    /** Whether a member or a sensor of {@code pool} faces another block than when the pool was built. */
    private static boolean turned(Pool pool) {
        for (int i = 0; i < pool.size; i++) {
            NetworkDevice member = pool.members[i];
            if (member instanceof LogicMover mover && mover.logicTarget() != Long.MIN_VALUE
                    && mover.logicTarget() != pool.machines[i]) {
                return true;
            }
            SensorSource sensor = pool.sensors[i];
            if (sensor != null && sensor.logicTarget() != pool.sensorTargets[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether member {@code i} of {@code pool} is idle: every member is when {@code idleValue} is empty. Otherwise by
     * its sensor's value, or else by reading its machine directly (a kept reading younger than {@code maxAge} ticks is
     * used as it is); a member with neither, or whose value is not there, counts as busy.
     */
    boolean idle(Pool pool, int i, String idleValue, long idle, long gameTime, long maxAge) {
        if (idleValue.isEmpty()) {
            return true;
        }
        SensorSource sensor = pool.sensors[i];
        if (sensor != null) {
            return sensor.logicValueFound(idleValue) && Pools.idle(sensor.logicValue(idleValue), idle);
        }
        Direction towards = pool.towards[i];
        NetworkState network = pool.network;
        if (towards == null || network == null) {
            return false;
        }
        NetworkDevice member = pool.members[i];
        MachineReader reader = readers.get(member);
        if (reader == null) {
            reader = new MachineReader(changed);
            readers.put(member, reader);
        }
        ServerLevel level = network.level();
        if (!reader.refresh(level, member.blockPos(), towards, member.ownerRecord(), TeamBudget.team(level, member),
                gameTime, maxAge)) {
            return false;
        }
        return reader.found(idleValue) && Pools.idle(reader.value(idleValue), idle);
    }

    /** A pool status value: members and idle members by {@code idleValue} and {@code idle}. */
    long status(NetworkState network, String labelKey, String idleValue, long idle, long gameTime) {
        Pool pool = pool(network, labelKey);
        int idleCount = 0;
        for (int i = 0; i < pool.size; i++) {
            if (idle(pool, i, idleValue, idle, gameTime, Long.MAX_VALUE)) {
                idleCount++;
            }
        }
        return Pools.packStatus(pool.size, idleCount);
    }

    /** How many times member {@code device}'s machine was read directly; for tests. */
    int directReads(NetworkDevice device) {
        MachineReader reader = readers.get(device);
        return reader == null ? 0 : reader.reads();
    }

    private static void build(Pool pool, ServerLevel level, List<NetworkDevice> labelled) {
        int size = 0;
        for (int d = 0; d < labelled.size(); d++) {
            if (labelled.get(d) instanceof LogicMover) {
                size++;
            }
        }
        // Built on label and device changes only, so fresh arrays cost nothing that matters.
        NetworkDevice[] members = new NetworkDevice[size];
        long[] machines = new long[size];
        @Nullable
        Direction[] towards = new Direction[size];
        @Nullable
        SensorSource[] sensors = new SensorSource[size];
        long[] sensorTargets = new long[size];
        int n = 0;
        for (int d = 0; d < labelled.size(); d++) {
            NetworkDevice device = labelled.get(d);
            if (!(device instanceof LogicMover)) {
                continue;
            }
            // Insertion by position, then by the machine served: the join order changes with every load.
            int k = n - 1;
            while (k >= 0 && before(device, members[k])) {
                members[k + 1] = members[k];
                k--;
            }
            members[k + 1] = device;
            n++;
        }
        for (int i = 0; i < n; i++) {
            NetworkDevice member = members[i];
            long machine = target(member);
            if (machine == Long.MIN_VALUE) {
                machine = neighbourMachine(level, member.blockPos());
            }
            machines[i] = machine;
            towards[i] = machine == Long.MIN_VALUE ? null : direction(member.blockPos(), machine);
            SensorSource sensor = sensorFor(machine, labelled);
            sensors[i] = sensor;
            sensorTargets[i] = sensor == null ? Long.MIN_VALUE : sensor.logicTarget();
        }
        pool.members = members;
        pool.machines = machines;
        pool.towards = towards;
        pool.sensors = sensors;
        pool.sensorTargets = sensorTargets;
        pool.size = n;
    }

    private static boolean before(NetworkDevice a, NetworkDevice b) {
        if (a.blockPos() != b.blockPos()) {
            return a.blockPos() < b.blockPos();
        }
        return target(a) < target(b);
    }

    private static long target(NetworkDevice device) {
        return device instanceof LogicMover mover ? mover.logicTarget() : Long.MIN_VALUE;
    }

    /**
     * An interface's machine: the first neighbour (in direction order) that is loaded and has a block entity that is
     * not a network device or a cable; {@link Long#MIN_VALUE} when there is none.
     */
    private static long neighbourMachine(ServerLevel level, long pos) {
        for (Direction side : DIRECTIONS) {
            long next = BlockPos.offset(pos, side);
            if (level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(BlockPos.getX(next)),
                    SectionPos.blockToSectionCoord(BlockPos.getZ(next))) == null) {
                continue;
            }
            BlockEntity entity = level.getBlockEntity(BlockPos.of(next));
            if (entity != null && !(entity instanceof NetworkDevice) && !(entity instanceof ConduitBlockEntity)) {
                return next;
            }
        }
        return Long.MIN_VALUE;
    }

    /** The side of block {@code from} that block {@code to} is on, or null when they do not share a face. */
    private static @Nullable Direction direction(long from, long to) {
        for (Direction side : DIRECTIONS) {
            if (BlockPos.offset(from, side) == to) {
                return side;
            }
        }
        return null;
    }

    /** The first labelled sensor that reads the block {@code machine}, or null. */
    private static @Nullable SensorSource sensorFor(long machine, List<NetworkDevice> labelled) {
        if (machine == Long.MIN_VALUE) {
            return null;
        }
        for (int d = 0; d < labelled.size(); d++) {
            if (labelled.get(d) instanceof SensorSource sensor && sensor.logicTarget() == machine) {
                return sensor;
            }
        }
        return null;
    }
}
