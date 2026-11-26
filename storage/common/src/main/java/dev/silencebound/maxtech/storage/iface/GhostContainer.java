package dev.silencebound.maxtech.storage.iface;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Ghost slots whose stack counts are targets, not items: up to {@link InterfaceWorker#MAX_ITEM_TARGET} whatever the
 * item's own stack size, so a filled bucket can stand for several buckets of fluid.
 */
public class GhostContainer extends SimpleContainer {
    public GhostContainer(int size) {
        super(size);
    }

    @Override
    public int getMaxStackSize() {
        return InterfaceWorker.MAX_ITEM_TARGET;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return InterfaceWorker.MAX_ITEM_TARGET;
    }
}
