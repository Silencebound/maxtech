package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneControlled;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A port in either form, as its GUI sees it: the block or the part on a cable. Its priority (the shared priority page)
 * counts in storage mode only. Server thread.
 */
public interface PortDevice extends SettingsCopyable, LogicMover, Prioritized, RedstoneControlled {
    PortMode mode();

    /** Sets the mode (import, export, storage); the look follows. Server side. */
    void setMode(PortMode mode);

    /** The ghost filter. */
    PortFilter filter();

    /** What moves things: tier, rates, speed cards and the research wait; in storage mode, the mounted storage. */
    PortWorker worker();

    /** The redstone mode (B09 item 9); see {@link PortWorker#redstone()}. */
    @Override
    default RedstoneControl redstone() {
        return worker().redstone();
    }

    /** The storage priority; see {@link PortWorker#priority()}. */
    @Override
    default int priority() {
        return worker().priority();
    }

    /** Sets the storage priority; see {@link PortWorker#setPriority}. */
    @Override
    default void setPriority(int priority) {
        worker().setPriority(priority);
    }

    /**
     * A port at a glance: its mode as an icon with the item rate (in storage mode, what it mounts), then its filter's
     * items as icons. O(filter size).
     */
    static void glance(PortDevice port, BlockInfoSink sink) {
        PortMode mode = port.mode();
        if (mode == PortMode.STORAGE) {
            sink.icon(Glance.PORT_STORAGE, busTarget(port.worker()));
        } else {
            sink.icon(mode == PortMode.IMPORT ? Glance.PORT_IMPORT : Glance.PORT_EXPORT, Component
                    .translatable(NetworkInfo.PORT_GLANCE_KEY, InfoFormat.compact(port.worker().itemsPerSecond())));
        }
        PortFilter filter = port.filter();
        List<InfoStack> stacks = new ArrayList<>(filter.getContainerSize());
        for (int i = 0; i < filter.getContainerSize(); i++) {
            ItemStack stack = filter.getItem(i);
            if (!stack.isEmpty()) {
                stacks.add(new InfoStack(ItemKey.of(stack), stack.getCount()));
            }
        }
        NetworkInfo.stacks(stacks, sink);
    }

    /**
     * What a storage bus shows as its target: the mounted block's name, or why it mounts nothing (no inventory, a land
     * claim, a loop).
     */
    static Component busTarget(PortWorker worker) {
        BusStatus status = worker.busStatus();
        if (status == BusStatus.MOUNTED) {
            return worker.mountedName().orElseGet(() -> Component.translatable(status.translationKey()));
        }
        return Component.translatable(status.translationKey());
    }

    /**
     * A port's details: its mode and both rates in words, and a round-robin export order; in storage mode its target,
     * access and priority.
     */
    static void details(PortDevice port, BlockInfoSink sink) {
        PortWorker worker = port.worker();
        if (port.mode() == PortMode.STORAGE) {
            sink.line(Component.translatable(PortContent.BUS_DETAIL_KEY, busTarget(worker),
                    Component.translatable(worker.busAccess().translationKey()), InfoFormat.number(worker.priority())));
            return;
        }
        sink.line(Component.translatable(NetworkInfo.PORT_DETAIL_KEY,
                Component.translatable(port.mode().translationKey()), InfoFormat.number(worker.itemsPerSecond()),
                InfoFormat.number(worker.fluidMbPerSecond())));
        if (port.mode() == PortMode.EXPORT && worker.order() != PortOrder.FILTER) {
            sink.line(Component.translatable(PortContent.ORDER_KEY,
                    Component.translatable(worker.order().translationKey())));
        }
    }

    /** Logic moves go through the worker: see {@link PortWorker#logicMove}. */
    @Override
    default long logicMove(StackKey key, long amount, boolean intoDevice, long gameTime) {
        return worker().logicMove(key, amount, intoDevice, gameTime);
    }

    @Override
    default long logicTarget() {
        return worker().facedPos();
    }

    @Override
    default long logicHeld(StackKey key) {
        return worker().facedAmount(key);
    }

    /** Whether {@code player}'s open GUI stays open: the port is still there and within reach. */
    boolean stillValid(Player player);

    /**
     * The filter, mode, storage access, priority and redstone mode for the blueprint card, as the item holds them; the
     * speed cards are contents and stay.
     */
    @Override
    default DeviceSettings copyableSettings() {
        return new DeviceSettings(DeviceSettings.kind("port"), () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            PortItems.write(components, filter(), mode(), worker());
            return components.build().filter(type -> !type.equals(ModDataComponents.PORT_SPEED_CARDS.get()));
        }, components -> {
            PortItems.readFilter(components, filter());
            filter().setChanged();
            PortItems.readBus(components, worker());
            setMode(PortItems.mode(components));
        });
    }
}
