package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import java.util.List;
import net.minecraft.core.BlockPos;
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
 * A sensor's screen: the values it read, live while the screen is open ({@link SensorView} after each read), and its
 * polling interval as a data slot with a menu button to step it, and whether a land claim blocks the target as another.
 * Opening the screen asks for a read.
 */
public final class SensorMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 216;
    public static final int HEIGHT = 196;
    public static final int CYCLE_POLL = 0;

    private final @Nullable SensorDevice device;
    private final Player viewer;
    private final DataSlot poll;
    private final DataSlot claim;
    private long sentVersion = -1;
    private boolean loaded = true;
    private List<SensorValue> values = List.of();

    private SensorMenu(int containerId, Inventory inventory, @Nullable SensorDevice device) {
        super(SensorContent.MENU.get(), containerId);
        this.device = device;
        this.viewer = inventory.player;
        if (device == null) {
            poll = DataSlot.standalone();
            claim = DataSlot.standalone();
        } else {
            claim = new DataSlot() {
                @Override
                public int get() {
                    return device.sensor().claimBlocked() ? 1 : 0;
                }

                @Override
                public void set(int value) {
                }
            };
            poll = new DataSlot() {
                @Override
                public int get() {
                    return device.sensor().settings().poll().id();
                }

                @Override
                public void set(int value) {
                }
            };
            device.sensor().refresh();
        }
        addDataSlot(poll);
        addDataSlot(claim);
    }

    /** The client's menu; the values arrive in a {@link SensorView}. */
    public static SensorMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new SensorMenu(containerId, inventory, null);
    }

    /** A server menu over {@code device} for {@code player}, as {@link #open} makes it; for tests. */
    public static SensorMenu server(int containerId, ServerPlayer player, SensorDevice device) {
        return new SensorMenu(containerId, player.getInventory(), device);
    }

    /** Opens {@code device}'s screen for {@code player}. */
    public static void open(ServerPlayer player, SensorDevice device, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> new SensorMenu(id, inventory, device), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** The synced polling interval. */
    public SensorPoll poll() {
        return SensorPoll.byId(poll.get());
    }

    /** Whether a land claim keeps the sensor from its target, as synced. */
    public boolean claimBlocked() {
        return claim.get() != 0;
    }

    /** The values the screen shows. */
    public List<SensorValue> values() {
        return values;
    }

    /** Whether the target's chunk is loaded, as last sent. */
    public boolean loaded() {
        return loaded;
    }

    void setView(boolean loaded, List<SensorValue> values) {
        this.loaded = loaded;
        this.values = List.copyOf(values);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        SensorDevice current = device;
        if (current == null || id != CYCLE_POLL || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        SensorWorker sensor = current.sensor();
        sensor.setSettings(sensor.settings().withPoll(sensor.settings().poll().next()));
        return true;
    }

    /** Sends the values when the sensor read since the last list sent (and once when the screen opens). */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        SensorDevice current = device;
        if (current == null) {
            return;
        }
        SensorWorker sensor = current.sensor();
        if (sensor.version() == sentVersion) {
            return;
        }
        sentVersion = sensor.version();
        loaded = sensor.targetLoaded();
        values = sensor.values();
        if (viewer instanceof ServerPlayer player && player.connection != null) {
            Platform.get().network().sendToPlayer(player, new SensorView(containerId, loaded, values));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        SensorDevice current = device;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current));
    }

    /** The shared label tab edits the sensor's label, block or part. */
    @Override
    public @Nullable NetworkDevice labelDevice() {
        return device;
    }
}
