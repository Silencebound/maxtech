package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.part.CableParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/** Finds the screen a payload names: a panel block at a position, or a display part on a cable's face. */
final class DisplayTargets {
    private DisplayTargets() {
    }

    /** The view of the screen at {@code pos} ({@code side} -1 for a panel block), or null if the level has none. */
    static @Nullable DisplayView view(Level level, BlockPos pos, int side) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        if (side < 0) {
            return level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel ? panel.view() : null;
        }
        if (side >= 6) {
            return null;
        }
        CableParts parts = StorageCableBlock.parts(level, pos);
        if (parts != null && parts.part(Direction.from3DDataValue(side)) instanceof DisplayPart part) {
            DisplayClientParts.add(part);
            return part.view();
        }
        return null;
    }

    /** The settings of the screen at {@code pos} ({@code side} -1 for a panel block), or null if the level has none. */
    static @Nullable DisplaySettings settings(Level level, BlockPos pos, int side) {
        if (!level.isLoaded(pos) || side >= 6) {
            return null;
        }
        if (side < 0) {
            return level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel ? panel.settings() : null;
        }
        CableParts parts = StorageCableBlock.parts(level, pos);
        return parts != null && parts.part(Direction.from3DDataValue(side)) instanceof DisplayPart part
                ? part.settings()
                : null;
    }
}
