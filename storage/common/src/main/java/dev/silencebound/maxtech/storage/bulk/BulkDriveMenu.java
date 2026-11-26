package dev.silencebound.maxtech.storage.bulk;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The bulk drive's screen: what it holds, and the priority tab. The kind travels once when the screen opens; the count
 * (four 16-bit data slots, as data slots sync shorts) and the priority stay live. The priority page's clicks are
 * vanilla menu button clicks ({@link PriorityControl}) checked against full access; no payload of its own, no slots.
 */
public final class BulkDriveMenu extends AbstractContainerMenu implements PriorityMenu, LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 64;
    private static final int COUNT_SLOTS = 4;
    private static final double REACH = 8.0;

    private final @Nullable BulkDriveBlockEntity drive;
    private final @Nullable ItemKey openedKey;
    private final DataSlot[] count = new DataSlot[COUNT_SLOTS];
    private final PriorityControl.Sync priority;

    private BulkDriveMenu(int containerId, @Nullable BulkDriveBlockEntity drive, @Nullable ItemKey openedKey) {
        super(BulkContent.MENU.get(), containerId);
        this.drive = drive;
        this.openedKey = openedKey;
        for (int i = 0; i < count.length; i++) {
            count[i] = addDataSlot(drive == null ? DataSlot.standalone() : new Count(drive, i));
        }
        priority = PriorityControl.sync(drive, this::addDataSlot);
    }

    /** The client's menu; the kind comes from the opening buffer, the rest from data slots. */
    public static BulkDriveMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        ItemKey key = buffer.readBoolean() ? ItemKey.STREAM_CODEC.decode(buffer) : null;
        return new BulkDriveMenu(containerId, null, key);
    }

    /** The server's menu over {@code drive}. */
    public static BulkDriveMenu forDrive(int containerId, BulkDriveBlockEntity drive) {
        return new BulkDriveMenu(containerId, drive, drive.store().key());
    }

    /** Opens {@code drive}'s screen for {@code player}. */
    public static void open(ServerPlayer player, BulkDriveBlockEntity drive, Component title) {
        ItemKey key = drive.store().key();
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDrive(id, drive), title), buffer -> {
                    buffer.writeBoolean(key != null);
                    if (key != null) {
                        ItemKey.STREAM_CODEC.encode(buffer, key);
                    }
                });
    }

    /** Items held, from the live data slots. */
    public long count() {
        long total = 0;
        for (int i = 0; i < COUNT_SLOTS; i++) {
            total |= (long) (count[i].get() & 0xFFFF) << (16 * i);
        }
        return total;
    }

    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    /** What the drive held when the screen opened, with the live count; null while it holds nothing. */
    public @Nullable BulkContents contents() {
        long held = count();
        return openedKey == null || held <= 0 ? null : new BulkContents(openedKey, held);
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        BulkDriveBlockEntity current = drive;
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
        BulkDriveBlockEntity current = drive;
        return current == null
                || (!current.isRemoved() && player.position().closerThan(current.getBlockPos().getCenter(), REACH)
                        && NetworkAccess.canConfigure(player, current));
    }

    /** One 16-bit part of the count, read live from the drive; server side. */
    private static final class Count extends DataSlot {
        private final BulkDriveBlockEntity drive;
        private final int index;

        Count(BulkDriveBlockEntity drive, int index) {
            this.drive = drive;
            this.index = index;
        }

        @Override
        public int get() {
            return (int) (drive.store().count() >>> (16 * index)) & 0xFFFF;
        }

        @Override
        public void set(int value) {
            // Server side the drive is the truth.
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return drive;
    }
}
