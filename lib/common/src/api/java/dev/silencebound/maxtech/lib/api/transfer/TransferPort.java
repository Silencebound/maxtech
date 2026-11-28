package dev.silencebound.maxtech.lib.api.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.ApiStatus;

/**
 * Moves resources between a block's own buffers (item slots, tanks, an energy buffer) and the blocks beside it, face by
 * face and per resource kind (ADR-0018); made by {@link TransferPorts#builder} and implemented by Lib only. A block
 * entity owns one port, starts it when it loads and stops it when it unloads or is removed.
 * <p>
 * The port never ticks: one scheduled task, in Lib's transfer group of the level or the owner's group
 * ({@link TransferPorts.Builder#tasks}), runs an operation when a buffer or neighbour changed, at most every
 * {@code transfer.minIntervalTicks}, and backs off while idle. Like Storage's devices it runs while started, whatever
 * the ticking status of its own chunk (a border chunk included); it moves only through faces whose neighbour is at full
 * status. Each operation is one root transfer transaction against the real handlers and counts only what moved, so
 * nothing is duped or lost. Rules every port keeps:
 * <ul>
 * <li>A face pushes or pulls, never both ({@link PortFaceMode}).</li>
 * <li>Between two active faces of Lib ports only a pushing face facing a pulling face moves, and the pusher does it
 * ({@link PortFaceMode#movesAgainst}); two pushing or two pulling faces move nothing between them. A neighbour port's
 * off face counts as a plain block.</li>
 * <li>Buffer roles hold between ports too. A port pushing into a neighbouring Lib port's pulling face inserts into that
 * port's buffers that take part in {@code PULL}, never into its push-only buffers, which the plain capability would
 * offer, and only what both ports' filters allow. Lib finds the neighbour through an internal registry of started ports
 * by level and position; it is not api, and a port that is not started is not in it. A neighbouring port's block must
 * also expose the kind's capability on that face, which shows it is still there. For a block that is not a Lib port the
 * neighbour's capability is used.</li>
 * <li>A move between two handlers that both lack transactions is refused; with one such side, Lib touches it last
 * (simulate it, move on the transactional side, carry out the foreign side, keep only what it took). A port's own
 * buffers always take part in transactions.</li>
 * </ul>
 * <p>
 * Host rule: the owner's block must expose the capability of each kind it moves (items, fluids, FE, a compat kind's) on
 * every face that can pull, and should expose it on all faces. A neighbouring port pushes into this port's pulling
 * buffers only while that capability is there, since it shows the block still exists; without it, nothing arrives. What
 * the capability itself offers is the owner's choice (the port never uses it).
 * <p>
 * Server thread only, like the buffers.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface TransferPort {
    /**
     * Starts the port at {@code pos} in its task group ({@link TransferPorts.Builder#tasks}, Lib's transfer group
     * without): it reads its neighbours and runs when there is work. Again does nothing.
     */
    void start(ServerLevel level, BlockPos pos);

    /** Stops the port and drops its neighbour caches; {@link #start} brings it back. Stopped, it does nothing. */
    void stop();

    /** The owner's block got a neighbour update: the port re-reads its neighbours and wakes. */
    void neighbourChanged();

    /**
     * A buffer changed outside the port's own moves (the machine produced or used something): the port wakes. Cheap (no
     * lookups or allocation) and safe to call from a buffer's change listener, even during the port's own moves, which
     * it ignores.
     */
    void bufferChanged();

    /** The kinds this port moves, each once, in the order the builder first added a buffer of it. */
    List<ResourceKind<?>> kinds();

    /**
     * The modes faces of {@code kind} may take: {@link PortFaceMode#OFF} and every mode one of the kind's buffers takes
     * part in; empty for a kind the port does not move.
     */
    Set<PortFaceMode> allowed(ResourceKind<?> kind);

    /**
     * Moves {@code face} of {@code kind} to the next allowed mode ({@link PortFaceMode#next}), as a wrench does, and
     * returns it; runs the settings listener. Returns {@link PortFaceMode#OFF} for a kind the port does not move.
     */
    PortFaceMode cycleFace(ResourceKind<?> kind, Direction face);

    /** The current settings. */
    PortSettings settings();

    /**
     * Replaces the settings, putting faces back within the allowed modes and keeping settings of kinds the port does
     * not move; runs the settings listener when anything changed.
     */
    void setSettings(PortSettings settings);

    /** What the port last did with {@code kind}; {@link PortStatus#OFF} for a kind it does not move. */
    PortStatus status(ResourceKind<?> kind);

    /**
     * Saves the settings into the owner's block entity data, the builder's defaults included, and the energy remainder
     * (the part of one MU an energy port holds between operations), if any.
     */
    void save(ValueOutput output);

    /**
     * Loads the settings and energy remainder saved by {@link #save}; data without settings loads as the builder's
     * {@link TransferPorts.Builder#defaults} ({@link PortSettings#EMPTY} unless the owner set some), data without a
     * remainder (or a corrupt one) as none.
     */
    void load(ValueInput input);
}
