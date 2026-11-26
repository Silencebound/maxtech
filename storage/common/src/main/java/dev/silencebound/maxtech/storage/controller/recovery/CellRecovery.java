package dev.silencebound.maxtech.storage.controller.recovery;

import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A storage cell whose item is destroyed (burnt, blown up, despawned, fallen out of the world) would leave its contents
 * in the cell save data for good. With the recovery bin on, they move into the bin of the network the cell was last in
 * instead (M7C item 20): {@link CellStorage} remembers that network's controller when the cell leaves a drive. A cell
 * that was never in a drive of a controller network, whose controller is gone or not loaded, keeps its contents in the
 * save data as before. Server thread.
 */
public final class CellRecovery {
    private CellRecovery() {
    }

    /**
     * {@code stack}'s item entity in {@code level} was destroyed for good: moves its contents into its home network's
     * recovery bin. Returns whether it did; false for anything that is no cell with contents and a loaded home.
     */
    public static boolean lost(ServerLevel level, ItemStack stack) {
        if (!(stack.getItem() instanceof StorageCellItem) || StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt() <= 0) {
            return false;
        }
        CellData data = StorageCellItem.data(stack);
        return data != null && lost(level, data.id());
    }

    /**
     * The item holding the cell {@code id} was destroyed for good (a cell item, or a storage block item, B09 item 27):
     * moves the cell's contents into its home network's recovery bin. Returns whether it did; false with the recovery
     * bin off, for {@link CellData#NO_ID} or without a loaded home.
     */
    public static boolean lost(ServerLevel level, long id) {
        if (id == CellData.NO_ID || StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt() <= 0) {
            return false;
        }
        CellStorage storage = CellStorage.get(level.getServer());
        ControllerBlockEntity core = home(level, storage, id);
        return core != null && storage.takeAll(id, core::voided);
    }

    private static @Nullable ControllerBlockEntity home(ServerLevel level, CellStorage storage, long id) {
        String dimension = storage.homeDimension(id);
        if (dimension.isEmpty()) {
            return null;
        }
        BlockPos pos = BlockPos.of(storage.homePos(id));
        for (ServerLevel candidate : level.getServer().getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(dimension)) {
                return candidate.isLoaded(pos) && candidate.getBlockEntity(pos) instanceof ControllerBlockEntity core
                        ? core.primary()
                        : null;
            }
        }
        return null;
    }
}
