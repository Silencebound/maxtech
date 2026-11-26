package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.bridge.ExportedStorage;
import dev.silencebound.maxtech.storage.bridge.ForeignNetwork;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The AE2 grid a bridge touches, reached through the grid node of the AE2 block in front of it (a cable, drive,
 * interface or any other block with a node on that side); the bridge adds no node of its own, so it uses no channel and
 * no AE2 energy. Reads and moves go through the grid's storage service ({@link MEStorage}), the same inventory AE2
 * terminals use, and only while the node is active (grid powered and booted). The Storage network goes in as a global
 * storage provider of that service ({@link Ae2Export}).
 *
 * <p>
 * The identity is the {@link IGrid}: AE2 builds a new grid object when grids merge or split, so {@link #connected()}
 * turns false once the node left the grid it was found in (or was destroyed), and the bridge finds the new grid. AE2
 * has no change signal for a grid's storage short of a grid node of our own (a storage watcher), so the bridge polls.
 * Server thread only.
 */
// Grids are compared by identity on purpose: a merge or split makes a new grid object.
@SuppressWarnings("ReferenceEquality")
final class Ae2Network implements ForeignNetwork {
    private static final IActionSource SOURCE = IActionSource.empty();

    private final IGridNode node;
    private final IGrid grid;
    private @Nullable Ae2Export exported;
    private @Nullable IGrid exportedTo;

    private Ae2Network(IGridNode node, IGrid grid) {
        this.node = node;
        this.grid = grid;
    }

    /** The finder: the grid of the AE2 node at {@code pos} on its {@code side}, or null when there is none. */
    // AE2 declares the capability's context as plain Void; NeoForge's no-context overload wants @Nullable Void.
    @SuppressWarnings("NullAway")
    static @Nullable ForeignNetwork find(ServerLevel level, BlockPos pos, Direction side) {
        IInWorldGridNodeHost host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos);
        IGridNode node = host == null ? null : host.getGridNode(side);
        IGrid grid = node == null ? null : gridOf(node);
        return node == null || grid == null ? null : new Ae2Network(node, grid);
    }

    /** The node's grid, or null once the node was destroyed (AE2 throws then). */
    private static @Nullable IGrid gridOf(IGridNode node) {
        try {
            return node.getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }

    @Override
    public Object identity() {
        return grid;
    }

    @Override
    public Component name() {
        return Component.translatable(Ae2Lang.NETWORK);
    }

    @Override
    public boolean connected() {
        return gridOf(node) == grid;
    }

    /** The grid's storage when it may be used now, else null (unpowered, booting or gone). */
    private @Nullable MEStorage inventory() {
        return connected() && node.isActive() ? grid.getStorageService().getInventory() : null;
    }

    @Override
    public void forEachStored(StorageProvider.Visitor visitor) {
        MEStorage inventory = inventory();
        if (inventory == null) {
            return;
        }
        // Read live, not getCachedInventory(): while something watches the grid AE2 rebuilds that cache every tick,
        // outside the bridge's guard, so it would hold the Storage network's own export.
        KeyCounter counter = new KeyCounter();
        inventory.getAvailableStacks(counter);
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            long amount = entry.getLongValue();
            StackKey key = amount > 0 ? Ae2Keys.storage(entry.getKey()) : null;
            if (key != null) {
                visitor.visit(key, amount);
            }
        }
    }

    @Override
    public long insert(StackKey key, long amount, boolean simulate) {
        return move(key, amount, simulate, true);
    }

    @Override
    public long extract(StackKey key, long amount, boolean simulate) {
        return move(key, amount, simulate, false);
    }

    private long move(StackKey key, long amount, boolean simulate, boolean in) {
        MEStorage inventory = amount > 0 ? inventory() : null;
        AEKey what = inventory == null ? null : Ae2Keys.ae(key);
        if (inventory == null || what == null) {
            return 0;
        }
        Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        long moved = in ? inventory.insert(what, amount, mode, SOURCE) : inventory.extract(what, amount, mode, SOURCE);
        return Math.clamp(moved, 0, amount);
    }

    @Override
    public boolean setChangeListener(@Nullable Runnable listener) {
        return false;
    }

    @Override
    public void export(@Nullable ExportedStorage storage) {
        Ae2Export old = exported;
        IGrid oldGrid = exportedTo;
        if (old != null && oldGrid != null) {
            if (storage == old.storage() && oldGrid == grid) {
                return;
            }
            exported = null;
            exportedTo = null;
            oldGrid.getStorageService().removeGlobalStorageProvider(old);
        }
        if (storage != null && connected()) {
            Ae2Export now = new Ae2Export(storage);
            grid.getStorageService().addGlobalStorageProvider(now);
            exported = now;
            exportedTo = grid;
        }
    }
}
