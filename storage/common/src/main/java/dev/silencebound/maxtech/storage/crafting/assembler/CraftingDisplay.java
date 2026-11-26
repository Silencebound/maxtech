package dev.silencebound.maxtech.storage.crafting.assembler;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * A block entity that shows the item it is crafting behind glass (B09 item 29): the crafting assembler and the crafter.
 * The client's renderer reads it; the item comes from {@link AssemblerCore#craftingItem(long)}, synced in the block
 * entity's update.
 */
public interface CraftingDisplay {
    /** {@link #windowMask()} for glass on the four horizontal sides. */
    int HORIZONTAL = bit(Direction.NORTH) | bit(Direction.SOUTH) | bit(Direction.WEST) | bit(Direction.EAST);

    /** The item being crafted now, or empty while idle. Never change the stack. */
    ItemStack craftingItem();

    /** The sides with a window, one bit per {@link Direction#get3DDataValue()}; the item is hidden from the others. */
    int windowMask();

    /**
     * Whether a camera at ({@code dx}, {@code dy}, {@code dz}) from the block's centre sees into it through one of the
     * windows in {@code mask}: it is more than half a block out along that window's side, so in front of its plane.
     * Allocation-free; the renderer calls it every frame.
     */
    static boolean seenThroughWindow(int mask, double dx, double dy, double dz) {
        for (int i = 0; i < 6; i++) {
            Direction side = Direction.from3DDataValue(i);
            if ((mask & bit(side)) != 0 && dx * side.getStepX() + dy * side.getStepY() + dz * side.getStepZ() > 0.5) {
                return true;
            }
        }
        return false;
    }

    /** The {@link #windowMask()} bit of {@code side}. */
    static int bit(Direction side) {
        return 1 << side.get3DDataValue();
    }
}
