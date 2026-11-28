package dev.silencebound.maxtech.lib.api.transfer;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Makes {@link TransferPort}s (ADR-0018):
 *
 * <pre>{@code
 * port = TransferPorts.builder(network.tasks()).items(slots).fluids(tank).allowed(ResourceKinds.FLUID, PULL_ONLY)
 *         .onSettingsChanged(this::setChanged).build();
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
     * Collects a port's buffers, one per resource kind, and per kind the face modes it allows and its rate cap. Every
     * kind allows all modes and moves up to the {@code transfer.*PerOp} config per operation unless set otherwise. Not
     * thread-safe; use on one thread, then build.
     */
    public static final class Builder {
        @SuppressWarnings("UnusedVariable") // read by Lib's port implementation, which is not in this version
        private final TaskGroup tasks;
        private final Map<ResourceKind<?>, Object> buffers = new LinkedHashMap<>();
        private final Map<ResourceKind<?>, Set<PortFaceMode>> allowed = new LinkedHashMap<>();
        private final Map<ResourceKind<?>, Long> rates = new LinkedHashMap<>();
        @SuppressWarnings("UnusedVariable") // see tasks
        private Runnable onSettingsChanged = () -> {
        };

        private Builder(TaskGroup tasks) {
            this.tasks = Objects.requireNonNull(tasks, "tasks");
        }

        /** Moves items in and out of {@code slots}, as their roles allow (inputs take, outputs give). */
        public Builder items(ItemSlots slots) {
            return buffer(ResourceKinds.ITEM, slots);
        }

        /** Moves {@code tank}'s kind in and out of it. */
        public <K> Builder tank(ResourceTank<K> tank) {
            return buffer(tank.kind(), tank);
        }

        /** {@link #tank} for a fluid tank. */
        public Builder fluids(FluidTank tank) {
            return tank(tank);
        }

        /** Moves FE in and out of {@code endpoint}, a buffer outside any energy network, at the configured FE rate. */
        public Builder energy(EnergyEndpoint endpoint) {
            return buffer(ResourceKinds.ENERGY, endpoint);
        }

        /**
         * The modes faces of {@code kind} may take, {@link PortFaceMode#OFF} included (a face can always be off); for a
         * port that only pushes, say. Throws {@link IllegalArgumentException} without {@code OFF}.
         */
        public Builder allowed(ResourceKind<?> kind, Set<PortFaceMode> modes) {
            if (!modes.contains(PortFaceMode.OFF)) {
                throw new IllegalArgumentException("Allowed port face modes must include OFF: " + modes);
            }
            allowed.put(kind, Set.copyOf(EnumSet.copyOf(modes)));
            return this;
        }

        /**
         * Most of {@code kind} one operation moves, in the kind's unit, below the config's per-operation amount; a
         * player's rate cap ({@link PortSettings#rate}) can lower it further. Throws for a cap below 1.
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
         * {@code allowed} or {@code rate} names a kind without one. Lib's ports are not implemented in this version, so
         * it then throws {@link UnsupportedOperationException}.
         */
        public TransferPort build() {
            if (buffers.isEmpty()) {
                throw new IllegalStateException("A transfer port needs at least one buffer");
            }
            for (ResourceKind<?> kind : allowed.keySet()) {
                requireBuffer(kind, "allowed");
            }
            for (ResourceKind<?> kind : rates.keySet()) {
                requireBuffer(kind, "rate");
            }
            throw new UnsupportedOperationException("Transfer ports are not implemented in this Lib version");
        }

        private Builder buffer(ResourceKind<?> kind, Object buffer) {
            Objects.requireNonNull(buffer, "buffer");
            if (buffers.putIfAbsent(kind, buffer) != null) {
                throw new IllegalArgumentException("A transfer port takes one buffer per kind; " + kind + " has one");
            }
            return this;
        }

        private void requireBuffer(ResourceKind<?> kind, String what) {
            if (!buffers.containsKey(kind)) {
                throw new IllegalStateException(what + " names " + kind + ", which has no buffer");
            }
        }
    }
}
