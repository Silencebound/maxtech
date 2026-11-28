package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import java.util.function.Function;
import org.jetbrains.annotations.ApiStatus;

/**
 * Handle of one {@link ItemSlots} of a {@link MenuDefinition}'s host, declared with
 * {@link MenuDefinition.Builder#items}; {@link MenuDefinition.Builder#slots} shows runs of it as {@link MenuSlots}. The
 * server menu reads the host's slots through the getter; the client menu builds an empty copy from the shape, which
 * vanilla slot sync fills. Immutable; any thread.
 *
 * @param <H>
 *            the menu's host
 */
public final class MenuItems<H> {
    private final int index;
    private final Function<? super H, ItemSlots> getter;
    private final ItemSlots.Builder shape;

    MenuItems(int index, Function<? super H, ItemSlots> getter, ItemSlots.Builder shape) {
        this.index = index;
        this.getter = getter;
        this.shape = shape;
    }

    /** Position among the definition's inventories, from 0 in declaration order. */
    public int index() {
        return index;
    }

    /** The number of slots. */
    public int size() {
        return shape.size();
    }

    /** The host's slots; Lib's server menu calls it once when the menu opens. */
    @ApiStatus.Internal
    public ItemSlots get(H host) {
        return getter.apply(host);
    }

    /** The slots' shape, from which the client menu builds its empty copy. */
    @ApiStatus.Internal
    public ItemSlots.Builder shape() {
        return shape;
    }
}
