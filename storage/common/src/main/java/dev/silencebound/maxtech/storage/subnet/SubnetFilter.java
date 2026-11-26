package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;

/**
 * A subnet controller's filter: nine ghost slots naming what the two sides may see of each other. An item stands for
 * itself with its data components, a filled bucket for its fluid. With every slot empty everything passes. The keys are
 * worked out when a slot changes, so a match is one hash lookup. Server thread for the controller's own filter.
 */
public final class SubnetFilter extends SimpleContainer {
    public static final int SIZE = 9;

    private final Runnable onChanged;
    private ObjectOpenHashSet<StackKey> keys = new ObjectOpenHashSet<>();

    SubnetFilter(Runnable onChanged) {
        super(SIZE);
        this.onChanged = onChanged;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        rebuild();
        onChanged.run();
    }

    /** Re-reads the keys from the slots; also after loading. */
    void rebuild() {
        ObjectOpenHashSet<StackKey> found = new ObjectOpenHashSet<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                found.add(stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)
                        ? FluidKey.of(bucket.content)
                        : ItemKey.of(stack));
            }
        }
        keys = found;
    }

    /** Whether {@code key} may pass: always with an empty filter, else only what a slot names. */
    public boolean allows(StackKey key) {
        return keys.isEmpty() || keys.contains(key);
    }

    /** How many different things the filter names; 0 lets everything pass. */
    public int size() {
        return keys.size();
    }
}
