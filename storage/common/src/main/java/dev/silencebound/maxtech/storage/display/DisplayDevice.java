package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;

/** What the settings GUI works on: a panel (and through it its whole wall) or a display part. Server side. */
public interface DisplayDevice extends SettingsCopyable, ChannelFree {
    /** The settings the screen shows. */
    DisplaySettings settings();

    /** Gives the screen {@code settings}: every panel of a panel's wall, or the part. */
    void applySettings(DisplaySettings settings);

    /**
     * Makes the screen show {@code markup} as rich text ({@link DisplaySourceKind#TEXT}, syntax in
     * {@link DisplayText}), keeping its other settings; a panel's whole wall shows it. For code such as the showcase;
     * unlike a player's save it is not checked, so an icon of an unknown item draws nothing and an unreadable
     * placeholder reads 0. Server side.
     */
    default void showText(String markup) {
        applySettings(settings().withSource(DisplaySourceKind.TEXT).withText(markup));
    }

    /** Whether {@code player} may keep the GUI open: the screen is still there and in reach. */
    boolean stillValid(Player player);

    /** The display settings for the blueprint card, as the item holds them; a panel's paste covers its whole wall. */
    @Override
    default DeviceSettings copyableSettings() {
        return new DeviceSettings(DeviceSettings.kind("display"), () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            if (!settings().equals(DisplaySettings.DEFAULT)) {
                components.set(ModDataComponents.DISPLAY_SETTINGS.get(), settings());
            }
            return components.build();
        }, components -> {
            DisplaySettings read = DisplayItems.read(components);
            applySettings(read == null ? DisplaySettings.DEFAULT : read);
        });
    }
}
