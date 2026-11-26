package dev.silencebound.maxtech.storage.smartlabel;

import net.minecraft.world.item.ItemStack;

/** What a client knows of one label's values ({@link LabelUpdate}); the renderer reads it. Client main thread. */
public final class LabelView {
    private int status = LabelUpdate.NO_DATA;
    private int detail;
    private long value;
    private long max;
    private ItemStack icon = ItemStack.EMPTY;

    void update(int status, int detail, long value, long max) {
        this.status = status;
        this.detail = detail;
        this.value = value;
        this.max = max;
    }

    /** {@link LabelUpdate#NO_DATA} until the first update. */
    public int status() {
        return status;
    }

    public int detail() {
        return detail;
    }

    public long value() {
        return value;
    }

    public long max() {
        return max;
    }

    /** The stack the renderer draws the item's icon from; kept so it is made once per item. */
    public ItemStack icon() {
        return icon;
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon;
    }
}
