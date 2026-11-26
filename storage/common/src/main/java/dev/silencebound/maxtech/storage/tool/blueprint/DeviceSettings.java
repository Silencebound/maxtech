package dev.silencebound.maxtech.storage.tool.blueprint;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;

/**
 * One device's settings for the blueprint card ({@link SettingsCopyable}): its kind, which a paste must match, how to
 * write the settings as item components (the same components the device's item carries) and how to apply such
 * components to the live device, saving it and waking its work. A paste only ever sees components its own kind's
 * {@code copy} wrote. Server thread.
 *
 * @param kind
 *            what the settings fit; the card pastes only onto devices of the same kind
 * @param copy
 *            the settings as components, leaving out defaults like the device's item does
 * @param paste
 *            applies settings components; a missing component means that setting's default
 */
public record DeviceSettings(Identifier kind, Supplier<DataComponentMap> copy, Consumer<DataComponentGetter> paste) {
    /** The lang key of a kind's name, {@code blueprint.<namespace>.<path>}. */
    public static String kindKey(Identifier kind) {
        return "blueprint." + kind.getNamespace() + "." + kind.getPath().replace('/', '.');
    }

    /** A kind in Storage's namespace. */
    public static Identifier kind(String path) {
        return MaxtechStorage.id(path);
    }
}
