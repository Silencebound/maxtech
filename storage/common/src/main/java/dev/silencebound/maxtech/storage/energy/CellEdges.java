package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.energy.FeEdge;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The FE edges of one energy cell (MLE item 4): every side that touches another mod's FE block (not a Storage block,
 * cable or machine, and not another MaxTech energy block) gets one of Lib's {@link FeEdge}s in the cell's energy
 * network, so a generator or battery of another mod feeds the network even when it does not push, and the network's
 * leftover supply goes out to other mods' machines under Lib's export settings, at {@link EnergyPriority#LEFTOVERS
 * leftovers} priority behind the controller and the cells. Storage blocks never get an edge, so two Storage networks
 * never pass energy back and forth through edges; and since Storage's buffers give nothing to their energy network, an
 * edge only ever exports what generators supply, never stored energy.
 * <p>
 * Sides are looked at when the cell loads and when a neighbour changes, never per tick. What an edge holds (at most
 * about one pass of the network's demand) is saved as part of the cell's energy; on unload the edges are dropped
 * without giving it back (the save has it), and when the cell is broken they give it back to their neighbours and the
 * rest goes into the cell. Server thread only.
 */
public final class CellEdges {
    private static final Direction[] SIDES = Direction.values();

    private final EnergyCellBlockEntity cell;
    private final @Nullable FeEdge[] edges = new FeEdge[SIDES.length];
    private @Nullable EnergyNetwork network;
    /** Set once the cell unloaded or was broken; the edges are gone for good. */
    private boolean gone;

    CellEdges(EnergyCellBlockEntity cell) {
        this.cell = cell;
    }

    /** The edge on {@code side}, or null; for tests. */
    public @Nullable FeEdge edge(Direction side) {
        return edges[side.get3DDataValue()];
    }

    /** The cell loaded: looks at its sides. */
    void loaded() {
        refresh();
    }

    /** Looks at the cell's sides again: makes edges towards new FE neighbours and closes those whose neighbour went. */
    void refresh() {
        if (gone || cell.isRemoved() || !(cell.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = cell.getBlockPos();
        for (Direction side : SIDES) {
            BlockPos at = pos.relative(side);
            if (!level.isLoaded(at)) {
                // Unknown, not gone: an edge keeps its neighbour's capability cache, which sees it come back.
                continue;
            }
            int index = side.get3DDataValue();
            boolean wanted = foreignFe(level, at, side.getOpposite());
            FeEdge edge = edges[index];
            if (wanted && edge == null) {
                edge = FeEdge.create(level, at, side.getOpposite());
                edge.setExportPriority(EnergyPriority.LEFTOVERS);
                edges[index] = edge;
                EnergyNetwork current = network;
                if (current != null && !current.isDisposed()) {
                    edge.join(current);
                }
            } else if (!wanted && edge != null) {
                edges[index] = null;
                keep(edge.close());
            }
        }
        edgedChanged();
    }

    /** The cell joined {@code state} or left its network (null): the edges move with it. */
    void networkChanged(@Nullable NetworkState state) {
        EnergyNetwork next = state == null || gone ? null : state.energy();
        network = next;
        for (FeEdge edge : edges) {
            if (edge == null) {
                continue;
            }
            if (next != null) {
                edge.join(next);
            } else {
                edge.leave();
            }
        }
    }

    /** Whether any edge moves energy now (as of the last pass); the cell is then saved each tick. */
    boolean active() {
        for (FeEdge edge : edges) {
            if (edge != null && (edge.importing() != 0 || edge.exporting() != 0)) {
                return true;
            }
        }
        return false;
    }

    /** Tells the cell's store whether it has edges, so its pool asks {@link #active()} each tick. */
    private void edgedChanged() {
        boolean any = false;
        for (FeEdge edge : edges) {
            any |= edge != null;
        }
        cell.cellStore().setEdged(any);
    }

    /** MU the edges hold now, rounded down; saved as the cell's. */
    public long heldMu() {
        long fe = 0;
        for (FeEdge edge : edges) {
            if (edge != null) {
                fe += edge.heldFe();
            }
        }
        return ServerConfig.feRate().toMu(fe);
    }

    /**
     * The cell's chunk unloads after its save took {@link #heldMu()}: the edges are dropped without giving anything
     * back, which would count it twice.
     */
    void unloaded() {
        if (gone) {
            return;
        }
        gone = true;
        for (int i = 0; i < edges.length; i++) {
            FeEdge edge = edges[i];
            if (edge != null) {
                edge.restoreHeldFe(0);
                edge.close();
                edges[i] = null;
            }
        }
        edgedChanged();
    }

    /** The cell was broken (or replaced): the edges give back what they hold, and the rest goes into the cell. */
    void closed() {
        if (gone) {
            return;
        }
        gone = true;
        for (int i = 0; i < edges.length; i++) {
            FeEdge edge = edges[i];
            if (edge != null) {
                edges[i] = null;
                keep(edge.close());
            }
        }
        edgedChanged();
    }

    /** Puts {@code fe} an edge could not give back into the cell. */
    private void keep(long fe) {
        if (fe > 0) {
            cell.cellStore().receive(ServerConfig.feRate().toMu(fe));
        }
    }

    /**
     * Whether the block at {@code pos} shows FE on {@code side} and belongs to another mod's energy: no Storage device,
     * machine or cable, and no MaxTech energy endpoint.
     */
    private static boolean foreignFe(ServerLevel level, BlockPos pos, Direction side) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity instanceof StorageDeviceBlockEntity || entity instanceof StorageMachineBlockEntity
                || entity instanceof ConduitBlockEntity) {
            return false;
        }
        return FeLookups.BLOCK.find(level, pos, side) != null && EnergyLookups.BLOCK.find(level, pos, side) == null;
    }
}
