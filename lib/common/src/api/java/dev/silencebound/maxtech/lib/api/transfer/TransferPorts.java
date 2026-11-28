package dev.silencebound.maxtech.lib.api.transfer;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyRole;
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
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Makes {@link TransferPort}s (ADR-0018):
 *
 * <pre>{@code
 * port = TransferPorts.builder().fluids(inputTank, PULL_ONLY).fluids(outputTank, PUSH_ONLY)
 *         .items(outputSlots, PUSH_ONLY).onSettingsChanged(this::setChanged).build();
 * }</pre>
 *
 * A port runs in Lib's transfer group of the level it starts in. All ports of a level without a group of their own
 * share that one group, and with it one owner's budget ({@code scheduler.ownerBudgetMicros} and
 * {@code scheduler.ownerOpsPerTick}, as one Storage network has): operations past it wait for the next tick, so many
 * ports in a level slow down rather than cost more tick time. An owner with a budget of its own passes its group with
 * {@link Builder#tasks}.
 * <p>
 * Ports move items, tanks of any kind with a {@code KindBridge} (fluids built in, at {@code transfer.fluidPerOp} per
 * operation, which other tank kinds use too) and standalone energy buffers as FE (ADR-0018).
 */
@ApiStatus.Experimental
public final class TransferPorts {
    private TransferPorts() {
    }

    /**
     * A builder for a port whose operations run in Lib's transfer group unless {@link Builder#tasks} says otherwise.
     */
    public static Builder builder() {
        return new Builder();
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
        private @Nullable Function<ServerLevel, TaskGroup> tasks;
        private final List<Buffer> buffers = new ArrayList<>();
        private final Map<ResourceKind<?>, Long> rates = new LinkedHashMap<>();
        private PortSettings defaults = PortSettings.EMPTY;
        private Runnable onSettingsChanged = () -> {
        };

        private Builder() {
        }

        /**
         * Runs the port's operations in the group {@code groups} gives for the level, asked on every
         * {@link TransferPort#start}, instead of Lib's transfer group: for an owner whose devices share a budget of its
         * own (a network's group, say). The port never joins a closed group: when the group closes while the port is
         * started, the next event asks {@code groups} again, and while it gives a closed group the port runs in Lib's
         * transfer group.
         */
        public Builder tasks(Function<ServerLevel, TaskGroup> groups) {
            this.tasks = Objects.requireNonNull(groups, "groups");
            return this;
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
         * Adds {@code endpoint}, a standalone energy buffer, taking part in {@code modes}, as {@link #items} does. Only
         * a {@link EnergyRole#BUFFER buffer} outside any energy network is accepted: networked energy moves through
         * Lib's FE edges and is not a port buffer (ADR-0018). Throws {@link IllegalArgumentException} for another role
         * or an endpoint that is in a network, here and again in {@link #build()}, in case it joined one since; an
         * endpoint that joins a network after the build is left alone by the port.
         * <p>
         * The port moves FE with the FE handlers beside it, converted at the configured FE rate, up to
         * {@code transfer.energyPerOp} MU per operation (at least 1 FE). The fraction of one MU a conversion leaves is
         * kept by the port for the next operation and saved with it ({@link TransferPort#save}). The port ignores the
         * endpoint's {@code maxInput} and {@code maxOutput}; the owner caps the port through {@link #rate}.
         * <p>
         * The port changes the endpoint's stored energy directly, so {@code onChange} runs after the root commit of
         * each operation that changed it (or the port's remainder), never inside a transaction: mark the block entity
         * changed there, so the energy is saved. It must not move energy itself. The port skips the endpoint while it
         * is in an energy network; when it leaves its network, the owner must call {@link TransferPort#bufferChanged()}
         * so the port wakes and moves it again.
         */
        public Builder energy(EnergyEndpoint endpoint, Set<PortFaceMode> modes, Runnable onChange) {
            checkStandalone(endpoint);
            return buffer(ResourceKinds.ENERGY,
                    new TransferPortFactory.EnergyBuffer(endpoint, Objects.requireNonNull(onChange, "onChange")),
                    modes);
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

        /**
         * The settings a new port starts with, and that {@link TransferPort#load} falls back to for data saved without
         * settings (instead of {@link PortSettings#EMPTY}), so an owner can start with some faces on (ADR-0018). The
         * port puts them back within the allowed modes of its buffers, like loaded settings. A port always saves its
         * settings, so changing the defaults later never reconfigures ports that already exist.
         */
        public Builder defaults(PortSettings settings) {
            this.defaults = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /** Runs after the settings changed (wrench, screen, load excluded), so the owner can save and sync. */
        public Builder onSettingsChanged(Runnable listener) {
            this.onSettingsChanged = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Checks the builder and makes the port: throws {@link IllegalStateException} without a buffer, or when
         * {@code rate} names a kind without one, {@link IllegalArgumentException} for an energy buffer that joined an
         * energy network since it was added, item slots not made by {@code ItemSlots.builder}, a tank not made by
         * {@link ResourceTank#create} or {@link FluidTank#create}, or a tank of a kind without a registered
         * {@code KindBridge}.
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
            for (Buffer buffer : buffers) {
                if (buffer.buffer instanceof TransferPortFactory.EnergyBuffer energy) {
                    checkStandalone(energy.endpoint());
                }
            }
            List<TransferPortFactory.Buffer> specs = new ArrayList<>(buffers.size());
            for (Buffer buffer : buffers) {
                specs.add(new TransferPortFactory.Buffer(buffer.kind, buffer.buffer, buffer.modes));
            }
            return TransferPortFactory.get()
                    .create(new TransferPortFactory.Spec(tasks, specs, rates, defaults, onSettingsChanged));
        }

        /** The settings set with {@link #defaults}; {@link PortSettings#EMPTY} without. */
        PortSettings defaults() {
            return defaults;
        }

        private static void checkStandalone(EnergyEndpoint endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (endpoint.role() != EnergyRole.BUFFER) {
                throw new IllegalArgumentException(
                        "A port energy buffer must have the BUFFER role: " + endpoint.role());
            }
            if (endpoint.network().isPresent()) {
                throw new IllegalArgumentException(
                        "A port energy buffer must be outside any energy network; networked energy moves through FE edges");
            }
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
