package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEKey;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The AE2 grid of the dev showcase's bridges station: a creative energy cell touching the bridge, an ME drive with a 1k
 * item cell on one side of it and an ME glass cable carrying an ME terminal on the other. The drive is placed with its
 * default north front, which must not face the cell (a drive does not connect on its front), so it sits beside the cell
 * rather than behind it. Once the grid is powered and booted it is stocked with the station's marker item and the
 * bridge is placed, as the {@code ae2_*} GameTests do (the bridge then finds an active node at once). Dev tooling;
 * registered only with AE2 loaded.
 */
final class Ae2Showcase {
    private static final long STOCK = 256;

    private Ae2Showcase() {
    }

    static void register() {
        ShowcaseBridges.register(ShowcaseBridges.Foreign.AE2, Ae2Showcase::build);
    }

    private static ShowcaseBridges.Task build(ShowcaseBridges.Site site) {
        ServerLevel level = site.level();
        BlockPos cell = site.pos(0, 0);
        BlockPos drivePos = site.pos(1, 0);
        BlockPos cablePos = site.pos(-1, 0);
        level.setBlockAndUpdate(cell, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(drivePos, AEBlocks.DRIVE.block().defaultBlockState());
        if (level.getBlockEntity(drivePos) instanceof DriveBlockEntity drive) {
            drive.getInternalInventory().insertItem(0, AEItems.ITEM_CELL_1K.stack(), false);
        } else {
            site.warn("no AE2 drive at " + drivePos.toShortString());
        }
        if (PartHelper.setPart(level, cablePos, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT)) == null
                || PartHelper.setPart(level, cablePos, site.across().getOpposite(), null,
                        AEParts.TERMINAL.get()) == null) {
            site.warn("no AE2 terminal at " + cablePos.toShortString());
        }
        Direction towardsBridge = site.facing().getOpposite();
        AEKey marker = Ae2Keys.ae(ShowcaseBridges.Foreign.AE2.marker());
        return () -> {
            if (!level.getBlockState(cell).is(AEBlocks.CREATIVE_ENERGY_CELL.block())) {
                // Cleared meanwhile.
                return true;
            }
            IGridNode node = node(level, cell, towardsBridge);
            if (node == null || !node.isActive() || marker == null) {
                return false;
            }
            long stored = node.getGrid().getStorageService().getInventory().insert(marker, STOCK, Actionable.MODULATE,
                    IActionSource.empty());
            if (stored <= 0) {
                // The drive's cell is not in the grid's storage yet.
                return false;
            }
            site.placeBridge();
            return true;
        };
    }

    // AE2 declares the capability's context as plain Void; NeoForge's no-context overload wants @Nullable Void.
    @SuppressWarnings("NullAway")
    private static @Nullable IGridNode node(ServerLevel level, BlockPos pos, Direction side) {
        IInWorldGridNodeHost host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos);
        return host == null ? null : host.getGridNode(side);
    }
}
