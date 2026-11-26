package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import java.util.function.LongSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The storage block's screen: bytes used of its capacity, the kinds it holds, and the priority and label tabs. The
 * numbers stay live through data slots (16 bits each, as data slots sync shorts); the priority page's clicks are
 * vanilla menu button clicks ({@link PriorityControl}) checked against full access. No slots, no payload of its own.
 */
public final class StorageBlockMenu extends AbstractContainerMenu implements PriorityMenu, LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 64;
    private static final int LONG_PARTS = 4;
    private static final double REACH = 8.0;

    private final @Nullable StorageBlockEntity block;
    private final DataSlot[] used = new DataSlot[LONG_PARTS];
    private final DataSlot[] total = new DataSlot[LONG_PARTS];
    private final DataSlot[] kinds = new DataSlot[LONG_PARTS];
    private final PriorityControl.Sync priority;

    private StorageBlockMenu(int containerId, @Nullable StorageBlockEntity block) {
        super(StorageBlockContent.MENU.get(), containerId);
        this.block = block;
        addLong(used, block == null ? null : block::usedBytes);
        addLong(total, block == null ? null : block::totalBytes);
        addLong(kinds, block == null ? null : block::kinds);
        priority = PriorityControl.sync(block, this::addDataSlot);
    }

    private void addLong(DataSlot[] parts, @Nullable LongSupplier value) {
        for (int i = 0; i < parts.length; i++) {
            parts[i] = addDataSlot(value == null ? DataSlot.standalone() : new Part(value, i));
        }
    }

    /** The client's menu; everything comes from data slots. */
    public static StorageBlockMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new StorageBlockMenu(containerId, null);
    }

    /** The server's menu over {@code block}. */
    public static StorageBlockMenu forBlock(int containerId, StorageBlockEntity block) {
        return new StorageBlockMenu(containerId, block);
    }

    /** Opens {@code block}'s screen for {@code player}. */
    public static void open(ServerPlayer player, StorageBlockEntity block, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forBlock(id, block), title), buffer -> {
                });
    }

    private static long read(DataSlot[] parts) {
        long value = 0;
        for (int i = 0; i < parts.length; i++) {
            value |= (long) (parts[i].get() & 0xFFFF) << (16 * i);
        }
        return value;
    }

    /** Bytes used, live. */
    public long usedBytes() {
        return read(used);
    }

    /** The capacity in bytes, live. */
    public long totalBytes() {
        return read(total);
    }

    /** Kinds held, kept ones included, live. */
    public int kinds() {
        return (int) read(kinds);
    }

    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        StorageBlockEntity current = block;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.setPriority(priority);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        StorageBlockEntity current = block;
        return current == null
                || (!current.isRemoved() && player.position().closerThan(current.getBlockPos().getCenter(), REACH)
                        && NetworkAccess.canConfigure(player, current));
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return block;
    }

    /** One 16-bit part of a long, read live from the block; server side. */
    private static final class Part extends DataSlot {
        private final LongSupplier value;
        private final int index;

        Part(LongSupplier value, int index) {
            this.value = value;
            this.index = index;
        }

        @Override
        public int get() {
            return (int) (value.getAsLong() >>> (16 * index)) & 0xFFFF;
        }

        @Override
        public void set(int value) {
            // Server side the block is the truth.
        }
    }
}
