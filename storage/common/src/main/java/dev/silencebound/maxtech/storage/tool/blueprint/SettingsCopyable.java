package dev.silencebound.maxtech.storage.tool.blueprint;

import dev.silencebound.maxtech.lib.api.security.Owned;

/**
 * A device whose settings the blueprint card can copy and paste onto another device of the same kind (M6e). Devices
 * implement it with one method that hands out their {@link DeviceSettings}; block and part forms of a device give the
 * same kind, so a card copied from a port block pastes onto a port part. Settings only: never contents such as stock,
 * speed cards or a world device's tool, which would duplicate items. Server thread.
 */
public interface SettingsCopyable extends Owned {
    /** This device's settings as the card reads and writes them; may be built on each call. */
    DeviceSettings copyableSettings();
}
