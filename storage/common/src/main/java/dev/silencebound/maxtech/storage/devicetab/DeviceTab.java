package dev.silencebound.maxtech.storage.devicetab;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The side tabs a device screen can have (B09 item 21b, owner decision 2026-10-18): one column on the panel's right
 * edge, always in this order, showing only the tabs the device has, with no gap for a missing one. Pure, so the order
 * and visibility rules are unit-tested.
 */
public enum DeviceTab {
    /** The device's mode, its redstone mode and its status (logic, load shedding, channels). */
    MODE("mode", 0),
    /** The storage or rule priority. */
    PRIORITY("priority", 1),
    /** Upgrade cards. */
    UPGRADES("upgrades", 2),
    /** The device's label. */
    LABEL("label", 3),
    /** What the network may do with the device's target (a storage bus's read and write access). */
    ACCESS("access", 4);

    private final String name;
    private final int order;

    DeviceTab(String name, int order) {
        this.name = name;
        this.order = order;
    }

    /** The tab's place in the column: lower first. Explicit, so the owner's order never depends on declaration. */
    public int order() {
        return order;
    }

    /** The bit of this tab in a {@link #mask}. */
    public int bit() {
        return 1 << order;
    }

    /** The lang key of the tab's title. */
    public String titleKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".device_tab." + name;
    }

    /** The tabs whose bits are set in {@code mask}, in column order. */
    public static List<DeviceTab> shown(int mask) {
        List<DeviceTab> out = new ArrayList<>(values().length);
        for (DeviceTab tab : values()) {
            if ((mask & tab.bit()) != 0) {
                out.add(tab);
            }
        }
        out.sort((a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    /** The mask of {@code tabs}. */
    public static int mask(DeviceTab... tabs) {
        int mask = 0;
        for (DeviceTab tab : tabs) {
            mask |= tab.bit();
        }
        return mask;
    }

    /**
     * The tab that carries a device's problem badge (no channel, target missing): the mode tab when the device has one,
     * else the first tab shown; null without tabs.
     */
    public static @Nullable DeviceTab badgeTab(int mask) {
        if ((mask & MODE.bit()) != 0) {
            return MODE;
        }
        List<DeviceTab> shown = shown(mask);
        return shown.isEmpty() ? null : shown.get(0);
    }
}
