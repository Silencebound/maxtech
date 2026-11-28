package dev.silencebound.maxtech.lib.api.transfer;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Makes {@link TransferPort}s (ADR-0018):
 *
 * <pre>{@code
 * port = TransferPorts.builder(network.tasks()).fluids(inputTank, PULL_ONLY).fluids(outputTank, PUSH_ONLY)
 *         .items(outputSlots, PUSH_ONLY).onSettingsChanged(this::setChanged).build();
 * }</pre>
 *
 * Lib's port implementation is not in this version yet: {@link Builder#build()} checks the builder and then throws
 * {@link UnsupportedOperationException}.
 */
@ApiStatus.Experimental
public final class TransferPorts {
    private TransferPorts() {
    }

    /** A builder for a port whose operations run in {@code tasks}, the owner's task group. */
    public static Builder builder(TaskGroup tasks) {
        return new Builder(tasks);
    }

    /**
     * Collects a port's buffers and per kind its rate cap. A port can drive several buffers of one kind (an input tank
     * it pulls into and an output tank it pushes from, say), each with the modes it takes part in: per face and kind,
     * the face's mode picks the buffers, so a {@code PULL} face fills the kind's buffers that allow {@code PULL} and a
     * {@code PUSH} face empties those that allow {@code PUSH}. Within those, a move goes first-fit in the order the
     * buffers were added. The modes a face of a kind may take are {@link PortFaceMode#OFF} and those of the kind's
     * buffers; players set modes per face and kind ({@link PortSettings}), while buffer roles are the owner's code and
     * are not saved. Every kind moves up to the {@code transfer.*PerOp} config per operation unless {@link #rate} caps
     * it. Not thread-safe; use on one thread, then build.
     */
    public static final class Builder {
        @SuppressWarnings("UnusedVariable") // read by Lib's port implementation, which is not in this version
        private final TaskGroup tasks;
        private final List<Buffer> buffers = new ArrayList<>();
        private final Map<ResourceKind<?>, Long> rates = new LinkedHashMap<>();
        @SuppressWarnings("UnusedVariable") // see tasks
        private Runnable onSettingsChanged = () -> {
        };

        private Builder(TaskGroup tasks) {
            this.tasks = Objects.requireNonNull(tasks, "tasks");
        }

        /**
         * Adds {@code slots} as an item buffer taking part in {@code modes} ({@code PUSH}, {@code PULL} or both;
         * {@code OFF} is ignored). Within it the slot roles still apply: pulled items go only into input slots, and
         * pushed items come only from output slots. Throws {@link IllegalArgumentException} without {@code PUSH} or
         * {@code PULL}.
         */
        public Builder items(ItemSlots slots, Set<PortFaceMode> modes) {
            return buffer(ResourceKinds.ITEM, slots, modes);
        }

        /** Adds {@code tank} as a buffer of its kind taking part in {@code modes}, as {@link #items} does. */
        public <K> Builder tank(ResourceTank<K> tank, Set<PortFaceMode> modes) {
            return buffer(tank.kind(), tank, modes);
        }

        /** {@link #tank} for a fluid tank. */
        public Builder fluids(FluidTank tank, Set<PortFaceMode> modes) {
            return tank(tank, modes);
        }

        /**
         * Adds {@code endpoint}, a buffer outside any energy network, moving FE at the configured FE rate and taking
         * part in {@code modes}, as {@link #items} does.
         */
        public Builder energy(EnergyEndpoint endpoint, Set<PortFaceMode> modes) {
            return buffer(ResourceKinds.ENERGY, endpoint, modes);
        }

        /**
         * Most of {@code kind} one operation moves, in the kind's unit, below the config's per-operation amount; a
         * player's rate cap ({@link PortSettings#rate}, 0 for no player cap) can lower it further. Throws for a cap
         * below 1.
         */
        public Builder rate(ResourceKind<?> kind, long cap) {
            if (cap < 1) {
                throw new IllegalArgumentException("Port rate cap must be at least 1, got " + cap);
            }
            rates.put(kind, cap);
            return this;
        }

        /** Runs after the settings changed (wrench, screen, load excluded), so the owner can save and sync. */
        public Builder onSettingsChanged(Runnable listener) {
            this.onSettingsChanged = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Checks the builder and makes the port: throws {@link IllegalStateException} without a buffer, or when
         * {@code rate} names a kind without one. Lib's ports are not implemented in this version, so it then throws
         * {@link UnsupportedOperationException}.
         */
        public TransferPort build() {
            if (buffers.isEmpty()) {
                throw new IllegalStateException("A transfer port needs at least one buffer");
            }
            for (ResourceKind<?> kind : rates.keySet()) {
                if (allowed(kind).isEmpty()) {
                    throw new IllegalStateException("rate names " + kind + ", which has no buffer");
                }
            }
            throw new UnsupportedOperationException("Transfer ports are not implemented in this Lib version");
        }

        /** The modes faces of {@code kind} may take with the buffers added so far; empty without a buffer of it. */
        Set<PortFaceMode> allowed(ResourceKind<?> kind) {
            EnumSet<PortFaceMode> modes = EnumSet.noneOf(PortFaceMode.class);
            for (Buffer buffer : buffers) {
                if (buffer.kind == kind) {
                    modes.add(PortFaceMode.OFF);
                    modes.addAll(buffer.modes);
                }
            }
            return modes;
        }

        private Builder buffer(ResourceKind<?> kind, Object buffer, Set<PortFaceMode> modes) {
            Objects.requireNonNull(buffer, "buffer");
            EnumSet<PortFaceMode> active = EnumSet.noneOf(PortFaceMode.class);
            for (PortFaceMode mode : modes) {
                if (mode != PortFaceMode.OFF) {
                    active.add(mode);
                }
            }
            if (active.isEmpty()) {
                throw new IllegalArgumentException("A port buffer must take part in PUSH or PULL: " + modes);
            }
            buffers.add(new Buffer(kind, buffer, Set.copyOf(active)));
            return this;
        }

        private record Buffer(ResourceKind<?> kind, Object buffer, Set<PortFaceMode> modes) {
        }
    }
}
