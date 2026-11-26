package dev.silencebound.maxtech.storage.label;

import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * A device menu whose screen has the label tab ({@code LabelTab}). The tab asks the server for the label and sends
 * edits through {@link LabelPayloads}; the server applies them to {@link #labelDevice()} after checking the menu's
 * container id, {@code stillValid} and that the player may configure the device. A new device's menu implements this
 * and its screen adds the tab in {@code init}; nothing else is needed.
 */
public interface LabelMenu {
    /**
     * Server side: the device whose label the tab edits. Null on the client, and for a menu opened without a device (a
     * terminal opened from an item), whose screen then shows no tab.
     */
    @Nullable
    NetworkDevice labelDevice();

    /** {@code holder} as a device when it is one, for menus that keep their device as a narrower type. */
    static @Nullable NetworkDevice device(@Nullable Object holder) {
        return holder instanceof NetworkDevice device ? device : null;
    }
}
