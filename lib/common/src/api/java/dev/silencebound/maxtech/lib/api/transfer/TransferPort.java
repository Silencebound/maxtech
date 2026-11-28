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
 * The port never ticks: one scheduled task in the owner's {@code TaskGroup} runs an operation when a buffer or
 * neighbour changed, at most every {@code transfer.minIntervalTicks}, and backs off while idle. Each operation is one
 * root transfer transaction against the real handlers and counts only what moved, so nothing is duped or lost. Rules
 * every port keeps:
 * <ul>
 * <li>A face pushes or pulls, never both ({@link PortFaceMode}).</li>
 * <li>A port never pulls from a neighbouring Lib port's pushing face; that port's push does the move
 * ({@link PortFaceMode#movesAgainst}). Two pushing or two pulling faces treat each other as plain neighbours.</li>
 * <li>A move between two handlers that both lack transactions is refused; with one such side, Lib touches it last
 * (simulate it, move on the transactional side, carry out the foreign side, keep only what it took).</li>
 * </ul>
 * <p>
 * Server thread only, like the buffers.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface TransferPort {
    /** Starts the port at {@code pos}: it reads its neighbours and runs when there is work. Again does nothing. */
    void start(ServerLevel level, BlockPos pos);

    /** Stops the port and drops its neighbour caches; {@link #start} brings it back. Stopped, it does nothing. */
    void stop();

    /** The owner's block got a neighbour update: the port re-reads its neighbours and wakes. */
    void neighbourChanged();

    /** A buffer changed outside the port's own moves (the machine produced or used something): the port wakes. */
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

    /** Saves the settings into the owner's block entity data. */
    void save(ValueOutput output);

    /** Loads settings saved by {@link #save}; data without them loads as {@link PortSettings#EMPTY}. */
    void load(ValueInput input);
}
