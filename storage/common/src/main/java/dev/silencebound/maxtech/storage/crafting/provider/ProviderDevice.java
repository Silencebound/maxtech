package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternHost;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * A pattern provider in any form, as its menu and the network see it: the block, the part on a cable or the crafter.
 */
public interface ProviderDevice extends PatternHost, SettingsCopyable {
    /** The pattern slots, the target machine and the dispatch. */
    ProviderLogic logic();

    @Override
    default boolean claimBlocked() {
        return logic().claimBlocked();
    }

    /** Whether {@code player}'s open menu stays open: the provider is still there and within reach. */
    boolean stillValid(Player player);

    @Override
    default PatternProvider patternProvider() {
        return logic();
    }

    /**
     * The blocking setting and the lock mode (B09 item 26) for the blueprint card, as the item holds them; never the
     * patterns. Block and part share a kind; the crafter has its own.
     */
    @Override
    default DeviceSettings copyableSettings() {
        ProviderLogic logic = logic();
        return new DeviceSettings(
                logic.crafter() ? ProviderContent.CRAFTER_SETTINGS_KIND : ProviderContent.SETTINGS_KIND, () -> {
                    DataComponentMap.Builder components = DataComponentMap.builder();
                    logic.write(components);
                    return components.build().filter(type -> type.equals(ProviderContent.nonBlockingType())
                            || type.equals(ProviderContent.lockType()));
                }, components -> {
                    if (!logic.crafter()) {
                        logic.setBlocking(!Boolean.TRUE.equals(components.get(ProviderContent.nonBlockingType())));
                    }
                    logic.setLockMode(components.getOrDefault(ProviderContent.lockType(), LockMode.NONE));
                });
    }

    /** Jade at a glance: the lock mode, in amber, while it holds batches. */
    static void glance(ProviderLogic logic, BlockInfoSink sink) {
        if (logic.locked() != null) {
            sink.status(BlockInfoSink.Tone.WARN, Component.translatable(ProviderContent.LOCKED_NOW_KEY,
                    Component.translatable(logic.lockMode().translationKey())));
        }
    }

    /** Jade's details: a lock mode other than none while it lets batches go (the glance names a holding one). */
    static void details(ProviderLogic logic, BlockInfoSink sink) {
        if (logic.lockMode() != LockMode.NONE && logic.locked() == null) {
            sink.line(Component.translatable(ProviderContent.LOCK_MODE_KEY,
                    Component.translatable(logic.lockMode().translationKey())));
        }
    }
}
