package dev.silencebound.maxtech.storage.registry;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Storage's creative tab (PRD 8.2: one tab per mod), listing what {@link ModContent} declared for it in registration
 * order, which {@link ModContent#init()} sets.
 */
public final class ModCreativeTabs {
    public static final String TITLE_KEY = "itemGroup." + MaxtechStorage.MOD_ID;

    private static final RegistryHelper.Registrar<CreativeModeTab> TABS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistryEntry<CreativeModeTab, CreativeModeTab> STORAGE = TABS.register("storage",
            () -> CreativeModeTab.builder().title(Component.translatable(TITLE_KEY))
                    .icon(() -> new ItemStack(ControllerContent.CONTROLLER.item().get()))
                    .displayItems((parameters, output) -> {
                        for (RegistryEntry<Item, ?> item : ModContent.CONTENT.creativeTabItems()) {
                            output.accept(item.get());
                        }
                    }).build());

    static {
        ModContent.CONTENT.translation(TITLE_KEY, "MaxTech Storage");
    }

    private ModCreativeTabs() {
    }

    /** Loads the class during mod construction so the tab is queued before registration runs. */
    public static void init() {
    }
}
