package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneControlled;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** An interface in either form, as its GUI and capabilities see it: the block or the part on a cable. */
public interface InterfaceDevice extends SettingsCopyable, LogicMover, RedstoneControlled {
    /** The stock, the handlers and the scheduled work. */
    InterfaceWorker worker();

    /** The redstone mode (B09 item 9); see {@link InterfaceWorker#redstone()}. */
    @Override
    default RedstoneControl redstone() {
        return worker().redstone();
    }

    /** Logic moves go through the stock: see {@link InterfaceWorker#logicMove}. */
    @Override
    default long logicMove(StackKey key, long amount, boolean intoDevice, long gameTime) {
        return worker().logicMove(key, amount, intoDevice);
    }

    @Override
    default long logicHeld(StackKey key) {
        return worker().stockAmount(key);
    }

    /** Whether {@code player}'s open GUI stays open: the interface is still there and within reach. */
    boolean stillValid(Player player);

    /**
     * The ghost slots and the redstone mode for the blueprint card, as the item holds them; the stock stays. A paste
     * sets each slot through the config container, as the GUI does, so the targets are read again.
     */
    @Override
    default DeviceSettings copyableSettings() {
        return new DeviceSettings(DeviceSettings.kind("interface"), () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            worker().write(components);
            return components.build().filter(type -> type.equals(InterfaceItems.configType())
                    || type.equals(ModDataComponents.REDSTONE_MODE.get()));
        }, components -> {
            Container config = worker().config();
            NonNullList<ItemStack> slots = NonNullList.withSize(config.getContainerSize(), ItemStack.EMPTY);
            ItemContainerContents copied = components.get(InterfaceItems.configType());
            if (copied != null) {
                copied.copyInto(slots);
            }
            for (int i = 0; i < slots.size(); i++) {
                config.setItem(i, slots.get(i));
            }
            redstone().setMode(RedstoneControl.mode(components));
        });
    }
}
